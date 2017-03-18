package net.fawad.sftpsample

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import org.apache.camel.CamelContext
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.log4j.ConsoleAppender
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


fun main(args: Array<String>) {
    LogManager.getRootLogger().addAppender(ConsoleAppender(PatternLayout()))

    SshServer.setUpDefaultServer().use { sshd ->
        sshd.initialize()
        sshd.start()
        doCamelConsumption().toCloseable().use { _ ->
            uploadFile("localhost", sshd.port)
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

fun uploadFile(server: String, port: Int) {
    val jsch = JSch()
    jsch.setKnownHosts("known_hosts")
    val session = jsch.getSession("bob", server, port)
    session.setPassword("password")
    session.connect()
    val sftp = session.openChannel("sftp")
    sftp.connect()
    if (sftp is ChannelSftp) {
        ByteArrayInputStream("Hello, world!".toByteArray(Charset.defaultCharset())).use { src ->
            sftp.put(src, "/tmp/foo.txt")
        }
    }
}

private fun CamelContext.toCloseable() = Closeable {
    fun close() = this.stop()
}

private fun doCamelConsumption(): CamelContext {

    val ctx = DefaultCamelContext()
    ctx.addRoutes(object : RouteBuilder() {
        override fun configure() {
            from("sftp://localhost:1222/tmp/foo.txt?username=bob&password=passwo111rd")
                    .to("stream:out")
        }

    })
    ctx.start()
    return ctx
}