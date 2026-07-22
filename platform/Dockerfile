FROM node:22-bookworm-slim AS build

WORKDIR /app
COPY platform/package.json platform/package-lock.json ./
RUN npm ci
COPY platform/tsconfig.json ./
COPY platform/src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim AS runtime

ENV NODE_ENV=production
WORKDIR /app
RUN useradd --system --uid 10001 --create-home plugin-platform
COPY --from=build /app/package.json /app/package-lock.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY platform/src/migrations ./src/migrations
COPY tools /repo/tools
COPY docs/third-party-service-manifest.schema.json /repo/docs/third-party-service-manifest.schema.json
COPY web/assets/schemas/third-party-service-manifest.schema.json /repo/web/assets/schemas/third-party-service-manifest.schema.json
RUN mkdir -p /data/artifacts && chown -R plugin-platform:plugin-platform /data /app
USER plugin-platform
EXPOSE 15020
CMD ["node", "dist/src/server.js"]
