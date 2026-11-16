FROM node:22-bookworm AS build
RUN apt-get update \
  && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json tsconfig.json ./
COPY packages ./packages
COPY apps ./apps
COPY tools ./tools
RUN npm ci && npm run build

FROM node:22-bookworm
RUN apt-get update \
  && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY --from=build /app/dist ./dist
COPY --from=build /app/packages/validation/rules ./packages/validation/rules
COPY --from=build /app/tools/validator/lib ./tools/validator/lib
COPY --from=build /app/tools/validator/build ./tools/validator/build
COPY --from=build /app/tools/validator/schxslt ./tools/validator/schxslt
RUN mkdir -p /data/validator-cache && chown -R node:node /data
ENV EFAKTURA_ENV=local \
    EFAKTURA_AUTH_MODE=emulator \
    EFAKTURA_PROVIDER=local-sandbox \
    EFAKTURA_PORT=8088 \
    EFAKTURA_HOST=0.0.0.0 \
    EFAKTURA_LOCAL_SEED=1 \
    EFAKTURA_STORE=sqlite \
    EFAKTURA_DATA_DIR=/data \
    EFAKTURA_VALIDATOR_CACHE=/data/validator-cache \
    EFAKTURA_EMULATOR_SECRET=local-only-not-a-production-secret \
    EFAKTURA_SANDBOX_WEBHOOK_SECRET=local-sandbox-webhook-not-production
USER node
VOLUME ["/data"]
EXPOSE 8088
CMD ["node", "--disable-warning=ExperimentalWarning", "dist/apps/api/src/index.js"]
