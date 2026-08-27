FROM node:22-bookworm-slim AS build

WORKDIR /app
COPY web/platform/package.json web/platform/package-lock.json ./
RUN npm ci
COPY web/platform/tsconfig.json ./
COPY web/platform/src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim AS runtime

ENV NODE_ENV=production
WORKDIR /app
RUN useradd --system --uid 10001 --create-home plugin-platform
COPY --from=build /app/package.json /app/package-lock.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY web/platform/src/migrations ./src/migrations
COPY tools /repo/tools
COPY docs/third-party-service-manifest.schema.json /repo/docs/third-party-service-manifest.schema.json
COPY docs/bjtu-marketplace.schema.json /repo/docs/bjtu-marketplace.schema.json
COPY plugin-tooling/contracts/capability-contracts.json /repo/plugin-tooling/contracts/capability-contracts.json
COPY web/assets/schemas/third-party-service-manifest.schema.json /repo/web/assets/schemas/third-party-service-manifest.schema.json
RUN mkdir -p /data/artifacts && chown -R plugin-platform:plugin-platform /data /app
USER plugin-platform
EXPOSE 15020
CMD ["node", "dist/src/server.js"]
