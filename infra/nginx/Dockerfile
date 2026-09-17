FROM nginx:1.27-alpine

# The only container with a published port in the production-style stack.
COPY infra/nginx/nginx.conf /etc/nginx/conf.d/default.conf

# No `nginx -t` here: the config names upstreams (backend, frontend) that exist only once Compose
# has created the network, so a build-time check fails on name resolution rather than on syntax.
# nginx validates the file at container start, where the names do resolve.
