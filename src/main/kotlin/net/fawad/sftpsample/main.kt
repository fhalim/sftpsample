package net.fawad.sftpsample

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import org.apache.camel.CamelContext
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.log4j.ConsoleAppender
import org.apache.log4j.Level
import org.apache.log4j.LogManager
import org.apache.log4j.PatternLayout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.subsystem.sftp.FileHandle
import org.apache.sshd.server.subsystem.sftp.SftpEventListener
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.function.Function


fun main(args: Array<String>) {
    val simulateLatency = true
    val latencyMs = 30000L
    LogManager.getRootLogger().addAppender(ConsoleAppender(PatternLayout()))
    LogManager.getRootLogger().level = Level.INFO
    val logger = LogManager.getLogger("main")

    SshServer.setUpDefaultServer().use { sshd ->
        val latch = CountDownLatch(1)
        sshd.initialize()
        sshd.start()
        setupCamelContext(Function { x ->
            logger.info("Received file with contents: $x")
            latch.countDown()
            x
        }).toCloseable().use { _ ->
            val proxyPort = sshd.port + 1
            val toxiClient = ToxiproxyClient("localhost", 8474)
            val proxy = toxiClient.createProxy("mocksftp", "localhost:${proxyPort}", "localhost:${sshd.port}")
            val uploadToPort = if (simulateLatency) {
                proxyPort
            } else {
                sshd.port
            }
            val onBeforeUpload = if (simulateLatency) {
                ->
                proxy.toxics().latency("slowuploads", ToxicDirection.UPSTREAM, latencyMs)
                Unit
            } else {
                ->
            }
            try {
                uploadFile("localhost", uploadToPort, onBeforeUpload)
            }finally{
                proxy.delete()
            }
            latch.await()
        }
    }
}

private fun SshServer.initialize() {
    val sshd = this
    val logger = LogManager.getLogger("main")
    sshd.port = 1222
    sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File("sample.ser"))
    sshd.passwordAuthenticator = PasswordAuthenticator { username, password, _ -> username == "bob" && password == "password" }
    val x = {
        val builder = SftpSubsystemFactory.Builder()
        builder.addSftpEventListener(object : SftpEventListener {
            override fun created(session: ServerSession?, path: Path?, attrs: MutableMap<String, *>?, thrown: Throwable?) {
                logger.info("Created!")
            }

            override fun written(session: ServerSession?, remoteHandle: String?, localHandle: FileHandle?, offset: Long, data: ByteArray?, dataOffset: Int, dataLen: Int, thrown: Throwable?) {
                logger.info("File ${localHandle?.file?.toUri()?.path} written")
            }
        })
        builder.build()
    }()
    sshd.subsystemFactories = listOf(x)
}

fun uploadFile(server: String, port: Int, onBeforeUpload: () -> Unit) {
    val jsch = JSch()
    jsch.setKnownHosts("known_hosts")
    val session = jsch.getSession("bob", server, port)
    session.setPassword("password")
    session.connect()
    val sftp = session.openChannel("sftp")
    sftp.connect()
    if (sftp is ChannelSftp) {
        val uploadInputstream = ByteArrayInputStream("Hello, world!".toByteArray(Charset.defaultCharset()))
        uploadInputstream.use { src ->
            try {
                sftp.stat("build/upload")
            } catch(e: Exception) {
                sftp.mkdir("build/upload")
            }
            onBeforeUpload()
            sftp.put(src, "build/upload/foo.txt")
        }
    }
}

private fun CamelContext.toCloseable() = Closeable { this.stop() }

private fun setupCamelContext(callback: Function<String, String>): CamelContext {
    val ctx = DefaultCamelContext()
    ctx.addRoutes(object : RouteBuilder() {
        override fun configure() {
            from("sftp://localhost:1222/build/upload?autoCreate=false&username=bob&password=password&knownHostsFile=known_hosts&move=.done&readLock=changed")
                    .unmarshal().string("UTF-8")
                    .bean(callback)
        }
    })
    ctx.start()
    return ctx
}
