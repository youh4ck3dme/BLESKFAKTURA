# Gradle test worker

## Príčina (Gradle 8.10.2)

`./gradlew test --no-daemon --stacktrace` na wrapperi 8.10.2 skončil **exit 1**. Nie je to assertion.

Daemon log, ešte pred dokončením testov:

```text
Unable to receive command from client socket connection from /127.0.0.1:36727 to /127.0.0.1:47526. Discarding connection.
Caused by: java.lang.IllegalArgumentException: Unexpected type tag 71 found.
    at org.gradle.internal.serialize.DefaultSerializerRegistry$TaggedTypeSerializer.read
```

Type tag 71 je bajt `0x47`, znak `G`. To je začiatok HTTP `GET`, nie Gradle správy.

Hneď nato test worker:

```text
org.gradle.internal.remote.internal.ConnectException: Could not connect to server [... port:44243, addresses:[/127.0.0.1]]
Caused by: java.net.ConnectException: Connection refused
    at org.gradle.process.internal.worker.child.SystemApplicationClassLoaderWorker.call
> Task :test FAILED
Process 'Gradle Test Executor 1' finished with non-zero exit value 1
```

Zdrojový port `36727` patril procesu `xai-grok-preview-proxy` v tomto sandboxe. Proxy otvorí každý nový localhost socket a pošle HTTP. Gradle 8.10.2 ten socket zvalí celý worker hub. Worker sa potom nepripojí (connection refused) a task skončí 1, aj keď testy ešte nespustil.

Nie je to pád `BridgeLiveTest` a nie je to `System.exit` v testoch. Live test neskôr ten istý typ kolízie zmenšuje: `EFAKTURA_PORT=0` a `EFAKTURA_HOST=127.0.0.1`, stdin z `/dev/null`, a SIGTERM volá `closeAllConnections`. To samotné 8.10.2 nezachráni, lebo worker zomrie skôr, než sa test spustí.

## Oprava

Wrapper je `gradle-9.3.1`. V nej je [gradle/gradle#35175](https://github.com/gradle/gradle/issues/27801): cudzie spojenie worker nestrhne.

Opakovaný beh 2026-11-16:

```text
./gradlew test --no-daemon
EXIT:0
BUILD SUCCESSFUL in 50s
```

V logu 9.3.1 proxy stále klepe na sockety (`Did not receive connection preamble within 10s`). Task prežije. JUnit: 10 testov, 0 failures, 0 errors. `Unexpected type tag 71` v tom logu nie je.

Závislosti okrem wrappera sa nezdvíhali.
