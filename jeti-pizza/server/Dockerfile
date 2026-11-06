FROM node:22-alpine
WORKDIR /app
COPY package.json menu.json server.js store.js ./
COPY public ./public
RUN mkdir /data && chown node:node /data
ENV PORT=8080 DB_PATH=/data/pizza.db
VOLUME /data
EXPOSE 8080
USER node
CMD ["node", "--disable-warning=ExperimentalWarning", "server.js"]
