# Copyright 2026 suhong
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# @author suhong
# syntax=docker/dockerfile:1
# tenant-service (8081) - multi-stage build. Build context must be the repository root:
#   docker build -f deploy/dockerfiles/tenant-service.Dockerfile -t tenancy/tenant-service .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# The parent pom lists every module, so all module descriptors must be present even
# though we only compile tenant-service and its dependencies.
COPY pom.xml .
COPY agent-kernel/pom.xml agent-kernel/pom.xml
COPY tenancy-common/pom.xml tenancy-common/pom.xml
COPY tenant-service/pom.xml tenant-service/pom.xml
COPY approval-service/pom.xml approval-service/pom.xml
COPY agent-service/pom.xml agent-service/pom.xml
COPY mcp-service/pom.xml mcp-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY tenancy-cli/pom.xml tenancy-cli/pom.xml
COPY tenancy-common/src tenancy-common/src
COPY tenant-service/src tenant-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl tenant-service -am -DskipTests package \
 && cp tenant-service/target/tenant-service-*.jar /workspace/app.jar

FROM eclipse-temurin:17-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system tenancy \
 && useradd --system --gid tenancy --home-dir /app --no-create-home --shell /usr/sbin/nologin tenancy
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
RUN chown -R tenancy:tenancy /app
USER tenancy
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8081/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
