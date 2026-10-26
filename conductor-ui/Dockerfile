FROM docker.io/library/node:24-alpine AS build
WORKDIR /app
COPY conductor-ui/package*.json ./
RUN npm ci
COPY conductor-ui/ ./
RUN npm run build

FROM docker.io/library/nginx:stable-alpine
COPY conductor-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
