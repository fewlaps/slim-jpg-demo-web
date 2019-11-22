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
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.netty.Netty
import io.ktor.util.InternalAPI
import io.ktor.util.encodeBase64
import io.ktor.util.hex
import kotlinx.html.*
import kotlinx.io.core.readBytes
import org.slf4j.event.Level

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@InternalAPI
@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    val port = System.getenv("PORT")?.toInt() ?: 8888
    io.ktor.server.engine.embeddedServer(Netty, port) {
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
                var sourceContentType = ""
                var sourceContent = ""
                var sourceWeight = 0
                var optimizedContent = ""
                var optimizedWeight = 0
                var optimizedJpegQuality = 0

                multipart.forEachPart { part ->
                    out += when (part) {
                        is PartData.FormItem -> {
                            "FormItem(${part.name},${part.value})"
                        }
                        is PartData.FileItem -> {
                            sourceContentType = part.contentType.toString()
                            val sourceImage = part.streamProvider().readBytes()
                            val optimizationResult = SlimJpg.file(sourceImage)
                                .maxFileWeightInKB(50)
                                .maxVisualDiff(1.0)
                                .keepMetadata()
                                .optimize()
                            val optimizedImage = optimizationResult.picture

                            sourceContent = sourceImage.encodeBase64()
                            sourceWeight = sourceImage.size
                            optimizedContent = optimizedImage.encodeBase64()
                            optimizedWeight = optimizedImage.size
                            optimizedJpegQuality = optimizationResult.jpegQualityUsed

                            if (optimizationResult.internalError != null) {
                                println("The optimization failed")
                                println("The source was a $sourceContentType")
                                println("The exception was ${optimizationResult.internalError?.message}");
                            } else {
                                println("The optimization was a success")
                                println("Optimization values: $optimizationResult")
                            }

                            "FileItem(${part.name},${part.originalFileName},${hex(sourceImage)})"
                        }
                        is PartData.BinaryItem -> {
                            "BinaryItem(${part.name},${hex(part.provider().readBytes())})"
                        }
                    }

                    part.dispose()
                }
                call.respondHtml {
                    body {
                        div("juxtapose") {
                            img {
                                src = "data:$sourceContentType;base64,$sourceContent"
                                attributes.put("data-label", "Original size: ${sourceWeight / 1024} KB")
                            }
                            img {
                                src = "data:image/jpeg;base64,$optimizedContent"
                                attributes.put(
                                    "data-label",
                                    "Optimized size: ${optimizedWeight / 1024} KB. Applied JPG quality: ${optimizedJpegQuality}%"
                                )
                            }
                        }
                        script { src = "https://cdn.knightlab.com/libs/juxtapose/latest/js/juxtapose.min.js" }
                        link {
                            rel = "stylesheet";
                            href = "https://cdn.knightlab.com/libs/juxtapose/latest/css/juxtapose.css"
                        }
                    }
                }
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
    }.start(wait = true)
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()

