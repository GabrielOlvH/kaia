# KAIA

KAIA (Kotlin AI Agents) is a library for building AI agents in Kotlin.

## Installation

### Repository Configuration

Before adding the dependency, you need to configure the repository:

#### Maven

```xml
<repositories>
    <repository>
        <id>gabrielolv-releases</id>
        <url>https://maven.gabrielolv.dev/repository/maven-releases/</url>
    </repository>
</repositories>
```

#### Gradle (Groovy)

```groovy
repositories {
    maven {
        url "https://maven.gabrielolv.dev/repository/maven-releases/"
    }
    mavenCentral()
}
```

#### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven {
        url = uri("https://maven.gabrielolv.dev/repository/maven-releases/")
    }
    mavenCentral()
}
```

### Dependency

#### Maven

```xml
<dependency>
    <groupId>dev.gabrielolv</groupId>
    <artifactId>kaia</artifactId>
    <version>VERSION</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'dev.gabrielolv:kaia:VERSION'
```

### Gradle (Kotlin DSL)

```kotlin
implementation("dev.gabrielolv:kaia:VERSION")
```

Replace `VERSION` with the latest version of the library.

## Usage

Add your usage examples here.

## License

```
MIT License

Copyright (c) 2025 Gabriel Henrique de Oliveira

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
