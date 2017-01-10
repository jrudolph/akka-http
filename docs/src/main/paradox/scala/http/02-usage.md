# 2. Usage

@@@ index
 * [migration](../../scala/http/migration-guide/index.md)
@@@


Akka HTTP is provided broken down into several components. For basic usage it is enough to include the
following dependency:

@@@vars
```sbt
"com.typesafe.akka" %% "akka-http" % "$project.version$" $crossString$
```
@@@

Akka-Http is currently cross-published for Scala 2.11 and 2.12.

## Modules

Akka HTTP is structured into several modules:

akka-http
: Higher-level functionality, like (un)marshalling, (de)compression as well as a powerful DSL
for defining HTTP-based APIs on the server-side, this is the recommended way to write HTTP servers
with Akka HTTP. Details can be found in the section @ref[High-level Server-Side API](routing-dsl/index.md#http-high-level-server-side-api)

akka-http-core
: A complete, mostly low-level, server- and client-side implementation of HTTP (incl. WebSockets)
Details can be found in sections @ref[Low-Level Server-Side API](low-level-server-side-api.md#http-low-level-server-side-api) and @ref[Consuming HTTP-based Services (Client-Side)](client-side/index.md#http-client-side)

akka-http-testkit
: A test harness and set of utilities for verifying server-side service implementations

akka-http-spray-json
: Predefined glue-code for (de)serializing custom types from/to JSON with [spray-json](https://github.com/spray/spray-json)
Details can be found here: @ref[JSON Support](common/json-support.md#akka-http-spray-json)

akka-http-xml
: Predefined glue-code for (de)serializing custom types from/to XML with [scala-xml](https://github.com/scala/scala-xml)
Details can be found here: @ref[XML Support](common/xml-support.md#akka-http-xml-marshalling)


## Configuration

Just like any other Akka module Akka HTTP is configured via [Typesafe Config](https://github.com/typesafehub/config).
Usually this means that you provide an `application.conf` which contains all the application-specific settings that
differ from the default ones provided by the reference configuration files from the individual Akka modules.

These are the relevant default configuration values for the Akka HTTP modules.

akka-http-core
:  @@snip [reference.conf](../../../../../../akka-http-core/src/main/resources/reference.conf)

akka-http
:  @@snip [reference.conf](../../../../../../akka-http/src/main/resources/reference.conf)

The other Akka HTTP modules do not offer any configuration via [Typesafe Config](https://github.com/typesafehub/config).

## Migration

See the @ref[migration guide](migration-guide/index.md).