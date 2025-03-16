package dev.gabrielolv.kaia

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.typed.createTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class TypedToolTest : FunSpec({

    context("TypedTool Creation and Execution") {

        @Serializable
        data class CalculatorParams(
            val operation: String,
            val a: Double,
            val b: Double
        )

        test("Create and execute typed tool with DSL") {
            // Create tool using DSL
            val calculatorTool = createTool<CalculatorParams> {
                name = "calculator"
                description = "Performs basic arithmetic calculations"

                execute { params ->
                    val result = when (params.operation) {
                        "add" -> params.a + params.b
                        "subtract" -> params.a - params.b
                        "multiply" -> params.a * params.b
                        "divide" -> if (params.b != 0.0) params.a / params.b else "Error: Division by zero"
                        else -> "Unknown operation: ${params.operation}"
                    }

                    ToolResult(
                        success = true,
                        result = result.toString()
                    )
                }
            }

            // Verify tool properties
            calculatorTool.name shouldBe "calculator"
            calculatorTool.description shouldBe "Performs basic arithmetic calculations"

            // Schema should contain type definitions
            calculatorTool.parameterSchema.toString() shouldContain "\"type\":\"object\""
            calculatorTool.parameterSchema.shouldContainKey("properties")

            // Execute tool with parameters
            val addParams = buildJsonObject {
                put("operation", "add")
                put("a", 10.0)
                put("b", 5.0)
            }

            runBlocking {
                val result = calculatorTool.execute(addParams)
                result.success shouldBe true
                result.result shouldBe "15.0"
            }
        }

        test("Handle errors in typed tool execution") {
            val calculatorTool = createTool<CalculatorParams> {
                name = "calculator"
                description = "Performs basic arithmetic calculations"

                execute { params ->
                    when (params.operation) {
                        "divide" -> {
                            if (params.b == 0.0) {
                                ToolResult(
                                    success = false,
                                    result = "Error: Division by zero"
                                )
                            } else {
                                ToolResult(
                                    success = true,
                                    result = (params.a / params.b).toString()
                                )
                            }
                        }
                        else -> {
                            ToolResult(
                                success = true,
                                result = "Operation executed"
                            )
                        }
                    }
                }
            }

            val divideByZeroParams = buildJsonObject {
                put("operation", "divide")
                put("a", 10.0)
                put("b", 0.0)
            }

            runBlocking {
                val result = calculatorTool.execute(divideByZeroParams)
                result.success shouldBe false
                result.result shouldBe "Error: Division by zero"
            }
        }

        test("Return error for invalid parameters") {
            val calculatorTool = createTool<CalculatorParams> {
                name = "calculator"
                description = "Performs basic arithmetic calculations"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Valid parameters"
                    )
                }
            }

            // Missing required parameter
            val invalidParams = buildJsonObject {
                put("operation", "add")
                // Missing "a" parameter
                put("b", 5.0)
            }

            runBlocking {
                val result = calculatorTool.execute(invalidParams)
                result.success shouldBe false
                result.result shouldContain "Parameter deserialization failed"
            }
        }
    }

    context("ToolManager with TypedTools") {

        @Serializable
        data class WeatherParams(
            val location: String,
            val units: String = "metric"
        )

        test("Register and execute typed tools with ToolManager") {
            val toolManager = ToolManager()

            // Create a typed tool
            val weatherTool = createTool<WeatherParams> {
                name = "weather"
                description = "Get weather information"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Weather for ${params.location} in ${params.units} units: Sunny, 22°C",
                        metadata = buildJsonObject {
                            put("location", params.location)
                            put("temperature", 22.0)
                            put("condition", "Sunny")
                        }
                    )
                }
            }

            // Register tool
            toolManager.registerTool(weatherTool)

            // Execute tool through manager
            val params = buildJsonObject {
                put("location", "New York")
                put("units", "imperial")
            }

            runBlocking {
                val result = toolManager.executeTool("weather", params)
                result.success shouldBe true
                result.result shouldContain "Weather for New York"
                result.result shouldContain "imperial"
                result.metadata.shouldContain("location", "New York")
            }
        }

        test("Execute tool from JSON string") {
            val toolManager = ToolManager()

            val weatherTool = createTool<WeatherParams> {
                name = "weather"
                description = "Get weather information"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Weather for ${params.location}: Sunny"
                    )
                }
            }

            toolManager.registerTool(weatherTool)

            val jsonParams = """{"location": "Tokyo", "units": "metric"}"""

            runBlocking {
                val result = toolManager.executeToolFromJson("weather", jsonParams)
                result.success shouldBe true
                result.result shouldContain "Weather for Tokyo"
            }
        }
    }

    context("Complex Parameter Types") {

        @Serializable
        data class OrderItem(
            val name: String,
            val quantity: Int,
            val price: Double
        )

        @Serializable
        data class OrderParams(
            val customerId: String,
            val items: List<OrderItem>,
            val address: Map<String, String>,
            val priority: Boolean = false
        )

        test("Handle complex nested parameters") {
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    val totalItems = params.items.sumOf { it.quantity }
                    val totalPrice = params.items.sumOf { it.price * it.quantity }

                    ToolResult(
                        success = true,
                        result = "Order created for customer ${params.customerId} with $totalItems items totaling $${totalPrice}",
                        metadata = buildJsonObject {
                            put("orderId", "ORD-12345")
                            put("totalItems", totalItems)
                            put("totalPrice", totalPrice)
                        }
                    )
                }
            }

            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                put("priority", true)
                putJsonArray("items") {
                    addJsonObject {
                        put("name", "Widget")
                        put("quantity", 5)
                        put("price", 9.99)
                    }
                    addJsonObject {
                        put("name", "Gadget")
                        put("quantity", 2)
                        put("price", 19.99)
                    }
                }
                putJsonObject("address") {
                    put("street", "123 Main St")
                    put("city", "Anytown")
                    put("zipCode", "12345")
                }
            }

            runBlocking {
                val result = orderTool.execute(orderJson)
                result.success shouldBe true
                result.result shouldContain "Order created for customer CUST-123"
                result.result shouldContain "7 items"
                result.metadata.shouldContainKey("orderId")
                result.metadata.shouldContain("totalItems", 7)
            }
        }
    }
})