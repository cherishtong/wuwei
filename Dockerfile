# ── wuwei cloud Dockerfile ──
# cd project-root && docker build -t wuwei-cloud .
# docker run -d -p 8080:8080 -v ~/.wuwei:/root/.wuwei wuwei-cloud

# ── Stage 1: Frontend build ──
FROM node:22-alpine AS frontend
WORKDIR /src
COPY wuwei-renderer/package.json wuwei-renderer/package-lock.json ./
RUN npm ci
COPY wuwei-renderer/ .
RUN npm run build

# ── Stage 2: Kernel build ──
FROM eclipse-temurin:21-jdk-alpine AS kernel
WORKDIR /src
COPY wuwei-core/build.gradle wuwei-core/settings.gradle ./
# Pre-download dependencies for layer caching
RUN echo "plugins { id 'java' }" > empty.gradle && \
    gradle --no-daemon -b empty.gradle dependencies 2>/dev/null || true
COPY wuwei-core/src/ src/
COPY wuwei-core/gradle* ./
RUN gradle fatJar --no-daemon -b build.gradle

# ── Stage 3: Runtime ──
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=kernel /src/build/libs/wuwei-kernel.jar .
COPY --from=frontend /src/dist ./dist

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:8080/ || exit 1

ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/wuwei-kernel.jar", "--profile", "cloud", "--web-root", "/app/dist"]
