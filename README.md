# JakartaEE DDD Examples

[![Build](https://github.com/hantsy/jakartaee-ddd-example/actions/workflows/build.yml/badge.svg)](https://github.com/hantsy/jakartaee-ddd-example/actions/workflows/build.yml) 
[![Integration Tests with GlassFish](https://github.com/hantsy/jakartaee-ddd-example/actions/workflows/it-with-glassfish.yml/badge.svg)](https://github.com/hantsy/jakartaee-ddd-example/actions/workflows/it-with-glassfish.yml)
[![Integration Tests with WildFly](https://github.com/hantsy/jakartaee-ddd-example/actions/workflows/it-wtih-wildfly.yml/badge.svg)](https://github.com/hantsy/jakartaee-ddd-example/actions/workflows/it-wtih-wildfly.yml)

This Library example demonstrates how to implement a modular DDD application using the latest Jakarta EE technologies, particularly with **Jakarta Data** to implement the `Repository` pattern, as described in Eric's book.

> [!WARNING]
> This project is still under development. Jakarta Data support is not available in GlassFish 8.0.0-M12, and I also encountered some issues when testing on WildFly 37.0.0.Beta1 preview.

## Prerequisites

Assume you have installed the following software:
* Java 21
* Maven 3.9.x
* Git
* Any code editor, such as IntelliJ IDEA Community or VSCode with Java extension pack.

## Build 

Clone the source code via:

```bash
git clone https://github.com/hantsy/jakartaee-ddd-example
```

Enter the project root folder and run the following command to build the project:

```bash
mvn clean package
```

Run the integration tests on GlassFish.

```bash
mvn clean verify -Parq-glassfish-managed
```

Run the integration tests on WildFly.

```bash
mvn clean verify -Parq-wildfly-managed
```

## Contribute

If you have any ideas about the implementation, please create an issue to discuss them. 




