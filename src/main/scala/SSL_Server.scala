import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory

import scala.collection.mutable
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.util.control.Exception._

import com.typesafe.config.ConfigFactory

import org.hirosezouen.hzutil._
import HZLog._

object SSL_Server {
    implicit val logger = getLogger(this.getClass.getName)

    val config = ConfigFactory.load()

    /* ---------------------------------------------------------------------- */

    def startEchoFuture(sslso: SSLSocket)
    {
        Future {
            log_debug("echo_future:start")
            val reader = new BufferedReader(new InputStreamReader(sslso.getInputStream))
            var loop_flag = true
            while(loop_flag) {
                val line = reader.readLine
                if(line == null) {
                    log_debug("connection closed.")
                    loop_flag = false
                } else {
                    println(line);
                }
            }
        }
    }

    /* ---------------------------------------------------------------------- */

    trait ResponseData {
        def nextData: Option[Array[Byte]]

        def getBytesFromFile(file: File): Array[Byte] = {
            log_debug(s"read data file=$file")
            val buff: Array[Byte] = new Array[Byte](4096)
            val sdfs = new FileInputStream(file)
            ultimately {
                if(sdfs != null)
                    sdfs.close
            } {
                val sdfbuff = mutable.ArrayBuffer.empty[Byte]
                var sdf_loop_flag = true
                while(sdf_loop_flag) {
                    val cnt = sdfs.read(buff)
                    if(cnt < 0)
                        sdf_loop_flag = false
                    else
                        sdfbuff ++= buff.take(cnt);
                }
                sdfbuff.toArray 
            }
        }
    }

    case class ResponseDataFile(fileName: String, fileName2: Option[String]) extends ResponseData {
        var fileList = fileName2 match {
            case Some(fn2) => List(fileName, fn2)
            case None => List(fileName)
        }

        def nextData: Option[Array[Byte]] = {
            val fl = fileList
            fileList = fl.tail :+ fl.head
            Some(getBytesFromFile(new File(fl.head)))
        }
    }

    case class ResponseDataDir(dirName: String) extends ResponseData {
        val dirLister = DirectoryLister(new File(dirName))
        def nextData: Option[Array[Byte]] = dirLister.nextFile match {
            case Some(file) => Some(getBytesFromFile(file))
            case None => None
        }
    }

    def sendData(data: Array[Byte], out: OutputStream) {
//        log_trace(f"%n${new String(data)}")
        if(config.getBoolean("send.data.is_split")) {
            def send_recurce(d: Array[Byte], i: Int) {
                if(d.nonEmpty) {
                    if(d.size < i)
                        out.write(d)
                    else {
                        out.write(d.take(i))
                        send_recurce(d.drop(i), i+1)
                    }
                }
            }
            send_recurce(data, 1)
        } else
            out.write(data)
    }

    def startHttpFuture(sslso: SSLSocket, responseData: ResponseData) {
        Future {
            log_debug("http_future:start")
            allCatch andFinally {
                sslso.close
            } either {
                val sslso_r = new BufferedReader(new InputStreamReader(sslso.getInputStream))
                var req_res_loop_flag = true
                while(req_res_loop_flag) {
                    val line = sslso_r.readLine
                    if(line == null)
                        req_res_loop_flag = false
                    else {
                        log_trace(line)
                        if(config.getBoolean("send.data.enable")) {
                            if(line.endsWith("HTTP/1.1")) {
                                responseData.nextData match {
                                    case Some(data) => {
                                        sendData(data, sslso.getOutputStream)
                                        if(config.getBoolean("send.data.is_after_end"))
                                            req_res_loop_flag = false
                                    }
                                    case None => req_res_loop_flag = false
                                }
                            }
                        } else {
                            log_trace("send.data.enable=false")
                        }
                    }
                }
            } match {
                case Right(_) => /* Nothing to do */
                case Left(th) => th.printStackTrace
            }
            sslso.close
        }
    }

    /* ---------------------------------------------------------------------- */

    def startServer() {
        val sslsvso_factory = SSLServerSocketFactory.getDefault()
        val sslsvso = sslsvso_factory.createServerSocket(config.getInt("server.port")).asInstanceOf[SSLServerSocket]
        log_debug(s"sslsvso=$sslsvso")

        val responseData =
            if(config.getBoolean("send.data.is_directory_listing"))
                ResponseDataDir(config.getString("send.data.directory"))
            else
                ResponseDataFile(config.getString("send.data.file"),
                                 if(config.hasPath("send.data.file_2"))
                                     Some(config.getString("send.data.file_2"))
                                 else
                                     None)

        var force_shutdown_count = 0
        while(true) {
            val sslso = sslsvso.accept().asInstanceOf[SSLSocket]
            log_info(s"a peer connected : ${sslso.getRemoteSocketAddress}")
            log_info(s"SSLSession :  ${sslso.getSession}")
            config.getString("server.mode") match {
                case "echo" => log_info("start echo server") ; startEchoFuture(sslso)
                case "http" => log_info("start http server") ; startHttpFuture(sslso, responseData)
                case m => log_error(s"config : invalid server.mode = $m")
            }
            if(config.getBoolean("server.force_shutdown.enable")) {
                force_shutdown_count += 1
                log_info(s"force shutdown count = $force_shutdown_count")
                if(config.getInt("server.force_shutdown.count") <= force_shutdown_count) {
                    log_info("execute force shutdown")
                    sys.exit(1)
                }
            }
        }
    }

    def main(ars: Array[String]) {
        System.setProperty("javax.net.ssl.keyStore", config.getString("keystore.file"))
        System.setProperty("javax.net.ssl.keyStorePassword", config.getString("keystore.passwd"))
        log_debug(s"""javax.net.ssl.keyStore=${System.getProperty("javax.net.ssl.keyStore")}""");
        log_debug(s"""javax.net.ssl.keyStorePassword=${System.getProperty("javax.net.ssl.keyStorePassword")}""");

        startServer()
    }
}

