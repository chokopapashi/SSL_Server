import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

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

    def start_echo_future(sslso: SSLSocket)
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

    def start_http_future(sslso: SSLSocket) {
        Future {
            log_debug("http_future:start")
            allCatch andFinally {
                l_t()
                sslso.close
            } either {
                l_t()
                /* prepare response data */
                val buff: Array[Byte] = new Array[Byte](4096)
                val sdfs = new FileInputStream(config.getString("send.data.file"))
                l_t()
                val response_data = ultimately {
                    l_t()
                    if(sdfs != null) sdfs.close
                } {
                    val sdfbuff = mutable.ArrayBuffer.empty[Byte]
                    var sdf_loop_flag = true
                    while(sdf_loop_flag) {
                        l_t()
                        val cnt = sdfs.read(buff)
                        if(cnt < 0)
                            sdf_loop_flag = false
                        else
                            sdfbuff ++= buff.take(cnt);
                    }
                    sdfbuff.toArray 
                }
                log_debug(f"%n${new String(response_data)}")
                l_t()
                /* request - response loop */
                val sslso_r = new BufferedReader(new InputStreamReader(sslso.getInputStream))
                val sslso_w = sslso.getOutputStream
                var req_res_loop_flag = true
                while(req_res_loop_flag) {
                    l_t()
                    val line = sslso_r.readLine
                    if(line == null)
                        req_res_loop_flag = false
                    else {
                        log_debug(line)
                        if(line.endsWith("HTTP/1.1")) {
                            l_t()
                            if(config.getBoolean("send.data.is_split")) {
                                l_t()
                                def send_recurce(data: Array[Byte], i: Int) {
                                    l_t(s"${data.size},$i")
                                    if(data.nonEmpty) {
                                        l_t()
                                        if(data.size < i) {
                                            l_t()
                                            sslso_w.write(data)
                                        } else {
                                            l_t()
                                            sslso_w.write(data.take(i))
                                            send_recurce(data.drop(i), i+1)
                                        }
                                    }
                                }
                                send_recurce(response_data, 1)
                            }
                            else {
                                l_t()
                                sslso_w.write(response_data)
                            }
                        }
                    }
                    if(config.getBoolean("send.data.is_after_end")) {
                        l_t()
                        req_res_loop_flag = false
                    }
                }
                l_t()
            } match {
                case Right(_) => {
                    l_t()
                }
                case Left(th) => {
                    l_t()
                    th.printStackTrace
                }
            }
            sslso.close
            l_t()
        }
    }

    def startServer() {
        val sslsvso_factory = SSLServerSocketFactory.getDefault()
        val sslsvso = sslsvso_factory.createServerSocket(config.getInt("server.port")).asInstanceOf[SSLServerSocket]

        while(true) {
            val sslso = sslsvso.accept().asInstanceOf[SSLSocket]
            log_info(s"a peer connected : ${sslso.getRemoteSocketAddress}")
            log_info(s"SSLSession :  ${sslso.getSession}")
            config.getString("server.mode") match {
                case "echo" => log_info("start echo server") ; start_echo_future(sslso)
                case "http" => log_info("start http server") ; start_http_future(sslso)
                case m => log_error(s"config : invalid server.mode = $m")
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

