package com.fewlaps.slimjpg

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.html.respondHtml
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.request.path
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.util.hex
import kotlinx.html.*
import kotlinx.io.core.readBytes
import org.slf4j.event.Level

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(Compression) {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024) // condition
        }
    }

    install(AutoHeadResponse)

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
    }

    routing {
        get("/") {
            call.respondHtml {
                body {
                    h1 { +"Welcome to the Slim JPG demo page!" }
                    p { +"Upload a picture and it will be optimized." }
                    form("/optimize", encType = FormEncType.multipartFormData, method = FormMethod.post) {
                        p {
                            label { +"Picture: " }
                            fileInput { name = "picture" }
                        }
                        p {
                            submitInput { value = "Optimize" }
                        }
                    }
                }
            }
        }

        post("/optimize") {
            val multipart = call.receiveMultipart()
            val out = arrayListOf<String>()
            multipart.forEachPart { part ->
                out += when (part) {
                    is PartData.FormItem -> {
                        "FormItem(${part.name},${part.value})"
                    }
                    is PartData.FileItem -> {
                        val bytes = part.streamProvider().readBytes()
                        "FileItem(${part.name},${part.originalFileName},${hex(bytes)})"
                    }
                    is PartData.BinaryItem -> {
                        "BinaryItem(${part.name},${hex(part.provider().readBytes())})"
                    }
                }

                part.dispose()
            }
            call.respondText(out.joinToString("; "))
        }

        install(StatusPages) {
            exception<AuthenticationException> { cause ->
                call.respond(HttpStatusCode.Unauthorized)
            }
            exception<AuthorizationException> { cause ->
                call.respond(HttpStatusCode.Forbidden)
            }

        }
    }
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()

