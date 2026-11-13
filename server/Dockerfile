# Cypher TV updater: the Go scheduler/publisher plus what scripts/build-dist.sh needs at run time
# (the Kotlin tools on a trimmed Java runtime, Node for the iptv-org/epg grabber, git, curl).
# Build context is the repository root:  docker build -f server/Dockerfile .

FROM golang:1.26-alpine AS go
WORKDIR /src
COPY server/go.mod server/*.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /updater .

# The preinstalled Gradle, not the wrapper: nothing to download but the dependencies.
FROM gradle:9-jdk17 AS jvm
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY core core
COPY tools tools
# The Android app is not needed here and would require the Android SDK.
RUN sed -i 's/, ":app"//' settings.gradle.kts && sed -i '/android.application/d' build.gradle.kts \
 && gradle --no-daemon -q :tools:installDist \
 && jlink --add-modules java.base,java.xml,java.logging,jdk.unsupported \
      --strip-debug --no-man-pages --no-header-files --compress=2 --output /jre

FROM node:22-bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends git openssh-client ca-certificates curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -g 10001 grab && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin grab
COPY --from=jvm /jre /opt/jre
COPY --from=jvm /src/tools/build/install/tools /opt/tools
COPY --from=go /updater /usr/local/bin/updater
ENV JAVA_HOME=/opt/jre PATH=/opt/jre/bin:$PATH TZ=Asia/Kolkata
VOLUME /data
EXPOSE 8787
ENTRYPOINT ["updater"]
