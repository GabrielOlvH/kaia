package dev.gabrielolv.kaia.examples.tools

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.registerTools
import dev.gabrielolv.kaia.core.tools.typed.ToolParameters

/**
 * Parameters for the weather tool
 */
object WeatherToolParams : ToolParameters() {
    val location = string("location")
    val unit = string("unit")
    
    init {
        required(location)
        // Add validation for unit
        regex(unit, Regex("^(celsius|fahrenheit)$"))
        // Set descriptions for better schema generation
        location.description = "The city and state, e.g. San Francisco, CA"
        unit.description = "The unit of temperature, either 'celsius' or 'fahrenheit'"
    }
}

/**
 * A simple weather tool for testing tool calling
 */
fun ToolManager.registerWeatherTool() {
    registerTools {
        typedTool<WeatherToolParams>(
            name = "get_weather",
            description = "Get the current weather in a given location. This tool returns the temperature and weather conditions for the specified city. The location should be provided as 'City, State' format. The temperature can be returned in either celsius or fahrenheit units."
        ) { params ->
            val location = params[WeatherToolParams.location]
            val unit = params[WeatherToolParams.unit] ?: "celsius"
            
            // Mock implementation that returns fake weather data
            val temperature = if (location.contains("San Francisco", ignoreCase = true)) {
                if (unit == "celsius") "18°C" else "64°F"
            } else if (location.contains("New York", ignoreCase = true)) {
                if (unit == "celsius") "22°C" else "72°F"
            } else if (location.contains("Seattle", ignoreCase = true)) {
                if (unit == "celsius") "12°C" else "54°F"
            } else {
                if (unit == "celsius") "20°C" else "68°F"
            }
            
            val condition = when {
                location.contains("San Francisco", ignoreCase = true) -> "Foggy"
                location.contains("New York", ignoreCase = true) -> "Partly Cloudy"
                location.contains("Seattle", ignoreCase = true) -> "Rainy"
                else -> "Sunny"
            }
            
            ToolResult(
                toolCallId = "",
                success = true,
                result = """
                    {
                      "location": "$location",
                      "temperature": "$temperature",
                      "condition": "$condition",
                      "unit": "$unit"
                    }
                """.trimIndent()
            )
        }
    }
}
