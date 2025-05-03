# KAIA LLM Providers

This module provides implementations of various LLM (Large Language Model) providers for the KAIA framework.

## Available Providers

### OpenAI Provider
The OpenAI provider allows you to use OpenAI's models like GPT-4, GPT-3.5, etc.

```kotlin
val provider = LLMProvider.openAI(
    apiKey = "your-openai-api-key",
    baseUrl = "https://api.openai.com/v1", // Optional, default value shown
    model = "gpt-4-turbo" // Optional, default value shown
)
```

For tools support, provide a `ToolManager`:

```kotlin
val provider = LLMProvider.openAI(
    apiKey = "your-openai-api-key",
    baseUrl = "https://api.openai.com/v1",
    model = "gpt-4-turbo",
    toolManager = yourToolManager
)
```

### Gemini Provider
The Gemini provider allows you to use Google's Gemini models.

```kotlin
val provider = LLMProvider.gemini(
    apiKey = "your-gemini-api-key",
    baseUrl = "https://generativelanguage.googleapis.com", // Optional, default value shown
    model = "gemini-2.5-pro-latest" // Optional, default value shown
)
```

#### Available Gemini Models

| Model | Description | Use Case |
|-------|-------------|----------|
| `gemini-2.5-pro-latest` | State-of-the-art model for complex reasoning | Advanced reasoning, complex tasks, long context |
| `gemini-2.5-flash-latest` | Fast, cost-effective model | Efficient processing with good quality results |
| `gemini-1.5-pro` | Stable model with large context window | Processing large amounts of data (up to 2M tokens) |
| `gemini-1.5-flash` | Stable, faster model | Quick responses for general tasks |
| `gemini-1.5-flash-8b` | Small model for simpler tasks | Low-complexity tasks with minimal latency |

### Anthropic Provider
The Anthropic provider allows you to use Anthropic's Claude models.

```kotlin
val provider = LLMProvider.anthropic(
    apiKey = "your-anthropic-api-key",
    baseUrl = "https://api.anthropic.com/v1", // Optional, default value shown
    model = "claude-3-7-sonnet-20250219" // Optional, default value shown
)
```

#### Available Claude Models

| Model | Description | Use Case |
|-------|-------------|----------|
| `claude-3-7-sonnet-20250219` | Latest and most intelligent model | High-intelligence tasks with extended thinking capability |
| `claude-3-5-sonnet-20241022` | Previous generation with high intelligence | Complex reasoning with good performance |
| `claude-3-5-haiku-20241022` | Fastest model in the Claude family | Intelligence at blazing speeds |
| `claude-3-haiku-20240307` | Compact, responsive model | Quick and accurate targeted performance |

### Custom Provider
The Custom provider allows you to create your own provider by specifying transformers for requests and responses.

```kotlin
val provider = LLMProvider.custom(
    url = "https://your-custom-api.com/endpoint",
    headers = mapOf("Authorization" to "Bearer your-token"),
    requestTransformer = { messages, options ->
        // Transform messages and options into your API format
        YourRequestFormat(...)
    },
    responseTransformer = { response ->
        // Transform response into LLMResponse
        LLMResponse(content = extractContent(response))
    }
)
```

## Usage Example

```kotlin
// Create a provider
val provider = LLMProvider.gemini(apiKey = "your-gemini-api-key")

// Create messages
val messages = listOf(
    LLMMessage.UserMessage("Hello, can you tell me about yourself?")
)

// Create options
val options = LLMOptions(
    systemPrompt = "You are a helpful AI assistant.",
    temperature = 0.7,
    maxTokens = 8192, // Adjust based on model capabilities
    stopSequences = listOf("STOP") // Optional stop sequences
)

// Generate responses
provider.generate(messages, options).collect { message ->
    when (message) {
        is LLMMessage.AssistantMessage -> println(message.content)
        is LLMMessage.SystemMessage -> println("System: ${message.content}")
        else -> println("Received message of type: ${message::class.simpleName}")
    }
}
```

See the `examples` module for more detailed usage examples.
