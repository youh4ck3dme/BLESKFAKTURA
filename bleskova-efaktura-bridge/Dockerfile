FROM node:22-bookworm
RUN apt-get update \
  && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json tsconfig.json ./
COPY packages ./packages
COPY apps ./apps
COPY tools ./tools
RUN npm ci && npm run build
ENV EFAKTURA_ENV=local \
    EFAKTURA_AUTH_MODE=emulator \
    EFAKTURA_PORT=8088
EXPOSE 8088
CMD ["node", "dist/apps/api/src/index.js"]
