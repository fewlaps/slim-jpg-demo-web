package com.fewlaps.slimjpg

import com.fewlaps.slimjpg.core.util.ReadableUtils
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
import java.text.NumberFormat

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
                var optimizedRatio = 0.0
                var explanationForTheUser = ""
                lateinit var optimizationResult: com.fewlaps.slimjpg.core.Result

                multipart.forEachPart { part ->
                    out += when (part) {
                        is PartData.FormItem -> {
                            "FormItem(${part.name},${part.value})"
                        }
                        is PartData.FileItem -> {
                            sourceContentType = part.contentType.toString()
                            val sourceImage = part.streamProvider().readBytes()
                            optimizationResult = SlimJpg.file(sourceImage)
                                .maxVisualDiff(0.5)
                                .optimize()
                            val optimizedImage = optimizationResult.picture

                            sourceContent = sourceImage.encodeBase64()
                            sourceWeight = sourceImage.size
                            optimizedContent = optimizedImage.encodeBase64()
                            optimizedWeight = optimizedImage.size
                            optimizedRatio = optimizationResult.savedRatio

                            if (optimizationResult.internalError != null) {
                                println("The optimization failed")
                                println("The source was a $sourceContentType")
                                println("The exception was ${optimizationResult.internalError?.message}");
                                explanationForTheUser =
                                    "The optimization failed. " +
                                            "There's something in your picture that the Java Image I/O package hasn't liked. " +
                                            "It told us that the error was '${optimizationResult.internalError?.message}'. Please, feel free to file an issue including this error and the picture you tried to compress."
                            } else if (sourceContent != optimizedContent) {
                                println("The optimization was a success and returned a different picture")
                                println("Optimization values: $optimizationResult")
                                explanationForTheUser =
                                    "The optimization was a success. " +
                                            "It took ${NumberFormat.getInstance().format(optimizationResult.elapsedTime)}ms, " +
                                            "saved ${ReadableUtils.formatFileSize(optimizationResult.savedBytes)} " +
                                            "which is the ${ReadableUtils.formatPercentage(optimizationResult.savedRatio)} of the file, " +
                                            "and applied a JPEG quality of ${optimizationResult.jpegQualityUsed}%."
                                if (optimizationResult.savedRatio < 0) {
                                    explanationForTheUser += " Why is the optimized file bigger than the original one? It's because the original one wasn't a JPG. That conversion from ${readeableContentType(
                                        sourceContentType
                                    )} to JPG gave a bigger JPG. Oooh, you touched the limits!"
                                }
                            } else {
                                println("The optimization was a success and returned a different file")
                                println("Optimization values: $optimizationResult")
                                explanationForTheUser =
                                    "The optimization was a success... but it returned exactly the same picture. " +
                                            "It happens when the original file was so well optimized that there's nothing better to do without losing any quality. " +
                                            "We call it artifical intelligence because being humble is too much."
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
                        h1 { +"Here's your lovely picture" }
                        p { +explanationForTheUser }
                        if (optimizationResult.internalError != null) {
                            a(
                                href = "https://github.com/Fewlaps/slim-jpg/issues/new",
                                target = "_blank"
                            ) { +"File an issue to the core of SlimJPG" }
                        }
                        form("/optimize", encType = FormEncType.multipartFormData, method = FormMethod.post) {
                            p {
                                label { +"Choose one more picture: " }
                                fileInput { name = "picture" }
                            }
                            p {
                                submitInput { value = "Optimize" }
                            }
                        }
                        div("juxtapose") {
                            img {
                                src = "data:$sourceContentType;base64,$sourceContent"
                                attributes["data-label"] = "Original size: ${sourceWeight / 1024} KB"
                            }
                            img {
                                src = "data:image/jpeg;base64,$optimizedContent"
                                attributes["data-label"] =
                                    "Optimized size: ${optimizedWeight / 1024} KB (${ReadableUtils.formatPercentage(
                                        1 - optimizedRatio
                                    )})"
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

private fun readeableContentType(sourceContentType: String): String {
    return sourceContentType.substring(sourceContentType.indexOf("/") + 1, sourceContentType.length).toUpperCase()
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()

