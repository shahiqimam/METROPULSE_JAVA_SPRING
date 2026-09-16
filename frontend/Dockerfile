FROM node:24-alpine AS build
WORKDIR /workspace
COPY frontend/package*.json ./
RUN npm ci
COPY frontend .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist/metropulse-frontend/browser /usr/share/nginx/html
