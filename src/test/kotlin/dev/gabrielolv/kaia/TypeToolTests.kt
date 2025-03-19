package dev.gabrielolv.kaia

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.builders.createTool
import dev.gabrielolv.kaia.core.tools.typed.ToolParameters
import dev.gabrielolv.kaia.core.tools.typed.ToolParametersInstance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

object NestedConversionParams : ToolParameters() {
    val nestedString = string("nestedString").withDescription("Nested string")
}
object NestedNullableParams : ToolParameters() {
    val nestedRequired = string("nestedRequired").withDescription("Required nested field")
    val nestedNullable = string("nestedNullable", null).withDescription("Nullable nested field")

    init {
        required(nestedRequired)
    }
}

object NullableParams : ToolParameters() {
    val requiredField = string("requiredField").withDescription("This field is required")
    val nullableField = string("nullableField", null).withDescription("This field can be null")
    val nestedObject = obj("nestedObject", NestedNullableParams::class, null)
        .withDescription("This nested object is nullable")

    init {
        required(requiredField)
    }
}
object ConversionParams : ToolParameters() {
    val stringField = string("stringField").withDescription("String field")
    val intField = int("intField").withDescription("Integer field")
    val nestedField = obj("nestedField", NestedConversionParams::class)
        .withDescription("Nested field")

    init {
        required(stringField, intField, nestedField)
    }
}


object PaymentParams : ToolParameters() {
    val amount = double("amount").withDescription("Payment amount")
    val currency = string("currency", "USD").withDescription("Payment currency")
    val method = string("method").withDescription("Payment method (credit, debit, bank)")
    val cardNumber = string("cardNumber", "").withDescription("Card number (required for credit/debit)")

    init {
        required(amount, method)
        min(amount, 0.01)

        // Custom validation: cardNumber is required when method is credit or debit
        custom("method-cardNumber", { params ->
            val methodStr = params[method] as? String ?: return@custom true
            if (methodStr == "credit" || methodStr == "debit") {
                val cardNum = params[cardNumber]
                return@custom cardNum.isNotBlank()
            }
            true
        }, "Card number is required when payment method is credit or debit")
    }
}

// Define a deeply nested structure
object DimensionsParams : ToolParameters() {
    val length = double("length").withDescription("Length in cm")
    val width = double("width").withDescription("Width in cm")
    val height = double("height").withDescription("Height in cm")

    init {
        required(length, width, height)
    }
}

object ProductDetails : ToolParameters() {
    val sku = string("sku").withDescription("Product SKU")
    val dimensions = obj("dimensions", DimensionsParams::class).withDescription("Product dimensions")

    init {
        required(sku, dimensions)
    }
}

object ComplexOrderItemParams : ToolParameters() {
    val name = string("name").withDescription("Item name")
    val quantity = int("quantity").withDescription("Quantity of items")
    val price = double("price").withDescription("Price per item")
    val details = obj("details", ProductDetails::class).withDescription("Product details")

    init {
        required(name, quantity, price, details)
        min(quantity, 1)
        min(price, 0.01)
    }
}

object ComplexOrderParams : ToolParameters() {
    val customerId = string("customerId").withDescription("Customer identifier")
    val items = objectList("items", ComplexOrderItemParams::class).withDescription("List of order items")

    init {
        required(customerId, items)
    }
}

// Define the item parameters
object OrderItemParams : ToolParameters() {
    val name = string("name").withDescription("Item name")
    val quantity = int("quantity").withDescription("Quantity of items")
    val price = double("price").withDescription("Price per item")

    init {
        required(name, quantity, price)
        min(quantity, 1)
        min(price, 0.01)
    }
}

// Define the address parameters
object AddressParams : ToolParameters() {
    val street = string("street").withDescription("Street address")
    val city = string("city").withDescription("City name")
    val zipCode = string("zipCode").withDescription("ZIP/Postal code")

    init {
        required(street, city, zipCode)
        regex(zipCode, Regex("\\d+"))
    }
}

object OrderParams : ToolParameters() {
    val customerId = string("customerId").withDescription("Customer identifier")
    val items = objectList("items", OrderItemParams::class).withDescription("List of order items")
    val address = obj("address", AddressParams::class).withDescription("Delivery address details")
    val priority = boolean("priority", false).withDescription("Order priority flag")

    init {
        required(customerId, items, address)

    }
}
object CalculatorParams : ToolParameters() {
    val operation = string("operation").withDescription("The operation to be executed")
    val a = double("a").withDescription("The first operand")
    val b = double("b").withDescription("The second operand")

    init {
        required(operation, a, b)
        min(a, 1.0)
    }
}

object WeatherParams : ToolParameters() {
    val location = string("location").withDescription("The location to get weather for")
    val units = string("units", "metric").withDescription("The units to use (metric or imperial)")

    init {
        required(location)
    }
}


class TypedToolTest : FunSpec({

    context("TypedTool Creation and Execution") {


        test("Create and execute typed tool with DSL") {
            // Create tool using DSL
            val calculatorTool = createTool<CalculatorParams> {
                name = "calculator"
                description = "Performs basic arithmetic calculations"

                execute { params ->
                    val b = params[CalculatorParams.b]
                    val a = params[CalculatorParams.a]
                    val result = when (params[CalculatorParams.operation]) {
                        "add" -> a + b
                        "subtract" -> a - b
                        "multiply" -> a * b
                        "divide" -> if (b != 0.0) a / b else "Error: Division by zero"
                        else -> "Unknown operation: ${params[CalculatorParams.operation]}"
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
            // Create tool using DSL
            val calculatorTool = createTool<CalculatorParams> {
                name = "calculator"
                description = "Performs basic arithmetic calculations"

                execute { params ->
                    val b = params[CalculatorParams.b]
                    val a = params[CalculatorParams.a]

                    when (params[CalculatorParams.operation]) {
                        "divide" -> {
                            if (b == 0.0) {
                                ToolResult(
                                    success = false,
                                    result = "Error: Division by zero"
                                )
                            } else {
                                ToolResult(
                                    success = true,
                                    result = (a / b).toString()
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

            // Verify tool properties
            calculatorTool.name shouldBe "calculator"
            calculatorTool.description shouldBe "Performs basic arithmetic calculations"

            // Execute tool with parameters that cause error
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
            // Create tool using DSL
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

            // Verify tool properties
            calculatorTool.name shouldBe "calculator"
            calculatorTool.description shouldBe "Performs basic arithmetic calculations"

            // Missing required parameter
            val invalidParams = buildJsonObject {
                put("operation", "add")
                // Missing "a" parameter
                put("b", 5.0)
            }

            runBlocking {
                val result = calculatorTool.execute(invalidParams)
                result.success shouldBe false
                result.result shouldContain "Validation failed"
            }
        }
    }

    context("ToolManager with TypedTools") {


        test("Register and execute typed tools with ToolManager") {
            val toolManager = ToolManager()

            // Create a typed tool using DSL
            val weatherTool = createTool<WeatherParams> {
                name = "weather"
                description = "Get weather information"

                execute { params ->
                    val location = params[WeatherParams.location]
                    val units = params[WeatherParams.units]

                    ToolResult(
                        success = true,
                        result = "Weather for $location in $units units: Sunny, 22°C",
                        metadata = buildJsonObject {
                            put("location", location)
                            put("temperature", 22.0)
                            put("condition", "Sunny")
                        }
                    )
                }
            }

            // Verify tool properties
            weatherTool.name shouldBe "weather"
            weatherTool.description shouldBe "Get weather information"

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
                result.metadata.shouldContain("location", JsonPrimitive("New York"))
            }
        }

        test("Execute tool from JSON string") {
            val toolManager = ToolManager()

            // Create a typed tool using DSL
            val weatherTool = createTool<WeatherParams> {
                name = "weather"
                description = "Get weather information"

                execute { params ->
                    val location = params[WeatherParams.location]
                    val units = params[WeatherParams.units]

                    ToolResult(
                        success = true,
                        result = "Weather for $location: Sunny"
                    )
                }
            }

            // Verify tool properties
            weatherTool.name shouldBe "weather"
            weatherTool.description shouldBe "Get weather information"

            // Register tool
            toolManager.registerTool(weatherTool)

            // Execute tool with JSON string parameters
            val jsonParams = """{"location": "Tokyo", "units": "metric"}"""

            runBlocking {
                val result = toolManager.executeToolFromJson("weather", jsonParams)
                result.success shouldBe true
                result.result shouldContain "Weather for Tokyo"
            }
        }
    }

    context("Complex Parameter Types") {



        test("Handle complex nested parameters") {
            // Create tool using DSL
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    val customerId = params[OrderParams.customerId]
                    val itemsArray = params[OrderParams.items]
                    val totalItems = itemsArray.sumOf { it[OrderItemParams.quantity] }
                    val totalPrice = itemsArray.sumOf {
                        val quantity = it[OrderItemParams.quantity]
                        val price = it[OrderItemParams.price]
                        quantity * price
                    }

                    ToolResult(
                        success = true,
                        result = "Order created for customer $customerId with $totalItems items totaling $$totalPrice",
                        metadata = buildJsonObject {
                            put("orderId", "ORD-12345")
                            put("totalItems", totalItems)
                            put("totalPrice", totalPrice)
                        }
                    )
                }
            }

            // Verify tool properties
            orderTool.name shouldBe "create_order"
            orderTool.description shouldBe "Create a new order"

            // Create complex nested JSON params
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
                result.metadata.shouldContain("totalItems", JsonPrimitive(7))
            }
        }
    }

    context("Edge Cases and Error Handling") {


        test("Empty list of complex objects") {
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    val itemsArray = params[OrderParams.items]

                    if (itemsArray.isEmpty()) {
                        return@execute ToolResult(
                            success = false,
                            result = "Order must contain at least one item"
                        )
                    }

                    ToolResult(
                        success = true,
                        result = "Order created with ${itemsArray.size} items"
                    )
                }
            }

            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                putJsonArray("items") { }  // Empty array
                putJsonObject("address") {
                    put("street", "123 Main St")
                    put("city", "Anytown")
                    put("zipCode", "12345")
                }
            }

            runBlocking {
                val result = orderTool.execute(orderJson)
                result.success shouldBe false
                result.result shouldBe "Order must contain at least one item"
            }
        }

        test("Validation errors in nested objects") {
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Order created successfully"
                    )
                }
            }

            // Invalid zipCode format in address
            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                putJsonArray("items") {
                    addJsonObject {
                        put("name", "Widget")
                        put("quantity", 5)
                        put("price", 9.99)
                    }
                }
                putJsonObject("address") {
                    put("street", "123 Main St")
                    put("city", "Anytown")
                    put("zipCode", "INVALID")  // Invalid format
                }
            }

            runBlocking {
                val result = orderTool.execute(orderJson)
                result.success shouldBe false
                result.result shouldContain "Validation failed"
            }
        }

        test("Validation errors in complex list items") {
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Order created successfully"
                    )
                }
            }

            // Invalid quantity (negative) in one of the items
            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                putJsonArray("items") {
                    addJsonObject {
                        put("name", "Widget")
                        put("quantity", -5)  // Negative quantity
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
                result.success shouldBe false
                result.result shouldContain "Validation failed"
            }
        }

        test("Missing required fields in complex list items") {
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Order created successfully"
                    )
                }
            }

            // Missing price in one of the items
            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                putJsonArray("items") {
                    addJsonObject {
                        put("name", "Widget")
                        put("quantity", 5)
                        // Missing price
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
                result.success shouldBe false
                result.result shouldContain "Validation failed"
            }
        }

        test("Malformed JSON for complex objects") {
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Order created successfully"
                    )
                }
            }

            // Address is a string instead of an object
            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                putJsonArray("items") {
                    addJsonObject {
                        put("name", "Widget")
                        put("quantity", 5)
                        put("price", 9.99)
                    }
                }
                put("address", "123 Main St, Anytown")  // String instead of object
            }

            runBlocking {
                val result = orderTool.execute(orderJson)
                result.success shouldBe false
                result.result shouldContain "Validation failed"
            }
        }

        test("Type mismatch in complex objects") {
            val orderTool = createTool<OrderParams> {
                name = "create_order"
                description = "Create a new order"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Order created successfully"
                    )
                }
            }

            // Quantity is a string instead of an integer
            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                putJsonArray("items") {
                    addJsonObject {
                        put("name", "Widget")
                        put("quantity", JsonPrimitive("five"))  // String instead of int
                        put("price", 9.99)
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
                result.success shouldBe false
                result.result shouldContain "Validation failed"
            }
        }

        test("Deeply nested complex objects") {


            val orderTool = createTool<ComplexOrderParams> {
                name = "complex_order"
                description = "Create a complex order with deeply nested objects"

                execute { params ->
                    val customerId = params[ComplexOrderParams.customerId]
                    val items = params[ComplexOrderParams.items]

                    // Access deeply nested properties
                    val firstItem = items.firstOrNull()
                    val firstItemDetails = firstItem?.get(ComplexOrderItemParams.details)
                    val firstItemSku = firstItemDetails?.get(ProductDetails.sku) ?: "unknown"

                    val dimensions = firstItemDetails?.get(ProductDetails.dimensions)
                    val firstItemHeight = dimensions?.get(DimensionsParams.height) ?: 0.0

                    ToolResult(
                        success = true,
                        result = "Order created for $customerId with item SKU: $firstItemSku, height: $firstItemHeight cm"
                    )
                }
            }

            // Create a deeply nested JSON structure
            val orderJson = buildJsonObject {
                put("customerId", "CUST-123")
                putJsonArray("items") {
                    addJsonObject {
                        put("name", "Complex Widget")
                        put("quantity", 3)
                        put("price", 29.99)
                        putJsonObject("details") {
                            put("sku", "WDG-123-XYZ")
                            putJsonObject("dimensions") {
                                put("length", 10.5)
                                put("width", 5.2)
                                put("height", 3.7)
                            }
                        }
                    }
                }
            }

            runBlocking {
                val result = orderTool.execute(orderJson)
                result.success shouldBe true
                result.result shouldContain "CUST-123"
                result.result shouldContain "WDG-123-XYZ"
                result.result shouldContain "3.7 cm"
            }
        }

        test("Custom validation across multiple fields") {


            val paymentTool = createTool<PaymentParams> {
                name = "process_payment"
                description = "Process a payment"

                execute { params ->
                    ToolResult(
                        success = true,
                        result = "Payment processed successfully"
                    )
                }
            }

            // Missing card number for credit payment
            val invalidPaymentJson = buildJsonObject {
                put("amount", 99.99)
                put("method", "credit")
                // Missing cardNumber
            }

            // Valid bank payment (no card needed)
            val validBankPaymentJson = buildJsonObject {
                put("amount", 99.99)
                put("method", "bank")
                // No card needed
            }

            // Valid credit payment with card
            val validCreditPaymentJson = buildJsonObject {
                put("amount", 99.99)
                put("method", "credit")
                put("cardNumber", "4111111111111111")
            }

            runBlocking {
                // Invalid payment should fail
                val invalidResult = paymentTool.execute(invalidPaymentJson)
                invalidResult.success shouldBe false
                invalidResult.result shouldContain "Validation failed"

                // Valid bank payment should succeed
                val validBankResult = paymentTool.execute(validBankPaymentJson)
                validBankResult.success shouldBe true

                // Valid credit payment should succeed
                val validCreditResult = paymentTool.execute(validCreditPaymentJson)
                validCreditResult.success shouldBe true
            }
        }

        test("Handling null values in complex objects") {


            val nullableTool = createTool<NullableParams> {
                name = "nullable_test"
                description = "Test handling of null values"

                execute { params ->
                    val required = params[NullableParams.requiredField]
                    val nullable = params.getOrNull(NullableParams.nullableField)
                    val nested = params.getOrNull(NullableParams.nestedObject)

                    ToolResult(
                        success = true,
                        result = "Required: $required, Nullable: ${nullable ?: "null"}, " +
                                "Nested: ${if (nested == null) "null" else "not null"}"
                    )
                }
            }

            // JSON with null values
            val jsonWithNulls = buildJsonObject {
                put("requiredField", "value")
                put("nullableField", JsonNull)
                // nestedObject is omitted
            }

            // JSON with nested object but null nested field
            val jsonWithNestedNulls = buildJsonObject {
                put("requiredField", "value")
                putJsonObject("nestedObject") {
                    put("nestedRequired", "nestedValue")
                    put("nestedNullable", JsonNull)
                }
            }

            runBlocking {
                // Test with nulls
                val nullResult = nullableTool.execute(jsonWithNulls)
                nullResult.success shouldBe true
                nullResult.result shouldContain "Required: value"
                nullResult.result shouldContain "Nullable: null"
                nullResult.result shouldContain "Nested: null"

                // Test with nested nulls
                val nestedResult = nullableTool.execute(jsonWithNestedNulls)
                nestedResult.success shouldBe true
                nestedResult.result shouldContain "Required: value"
                nestedResult.result shouldContain "Nested: not null"
            }
        }

        test("Converting between JSON formats") {

            // Create a params instance
            val params = ToolParametersInstance(ConversionParams::class)
            params[ConversionParams.stringField] = "test"
            params[ConversionParams.intField] = 42

            val nestedParams = ToolParametersInstance(NestedConversionParams::class)
            nestedParams[NestedConversionParams.nestedString] = "nested value"
            params[ConversionParams.nestedField] = nestedParams

            // Convert to JSON and back
            val jsonObject = params.toJsonObject()
            val jsonString = params.toJsonString()

            // Create a new instance from the JSON
            val newParams = ToolParametersInstance(ConversionParams::class).parseFromJson(jsonObject)
            val stringParams = ToolParametersInstance(ConversionParams::class).parseFromJsonString(jsonString)

            // Verify values are preserved
            newParams[ConversionParams.stringField] shouldBe "test"
            newParams[ConversionParams.intField] shouldBe 42
            newParams[ConversionParams.nestedField][NestedConversionParams.nestedString] shouldBe "nested value"

            stringParams[ConversionParams.stringField] shouldBe "test"
            stringParams[ConversionParams.intField] shouldBe 42
            stringParams[ConversionParams.nestedField][NestedConversionParams.nestedString] shouldBe "nested value"
        }
    }
})
