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
# Fast runtime image for local k3s verification: uses the jars already built by the
# Windows/Maven build instead of rebuilding inside the image (the dev box cannot reach
# Maven Central from the Windows host, but prebuilt jars already exist in */target).
#
#   docker build -f deploy/k8s/runtime.Dockerfile \
#     --build-arg JAR_PATH=tenant-service/target/tenant-service-0.9.0-SNAPSHOT.jar \
#     -t tenancy/tenant-service:1.0.0 .
#
# Production images stay the multi-stage deploy/dockerfiles/*.Dockerfile.
ARG BASE_IMAGE=eclipse-temurin:17-jre
FROM ${BASE_IMAGE}

# NOTE: eclipse-temurin:17-jre ships curl and wget, so compose healthchecks work
# without an apt-get layer (apt sources are unreachable on some networks).

ARG JAR_PATH
WORKDIR /app
COPY ${JAR_PATH} /app/app.jar
COPY skills /app/skills

RUN addgroup --system tenancy \
 && adduser --system --ingroup tenancy --no-create-home --shell /usr/sbin/nologin tenancy \
 && chown -R tenancy:tenancy /app
USER tenancy

ENV JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:MaxMetaspaceSize=192m"
ENV TENANCY_SKILLS_DIR=/app/skills
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
