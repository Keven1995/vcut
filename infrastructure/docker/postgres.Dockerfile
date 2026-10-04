FROM postgres:16.14-alpine3.22 AS postgres

# The upstream gosu binary is built with a vulnerable Go standard library.
# Alpine's su-exec provides the same user-switching interface needed by the
# official PostgreSQL entrypoint without the bundled Go runtime.
RUN apk add --no-cache su-exec \
    && rm /usr/local/bin/gosu \
    && ln -s /sbin/su-exec /usr/local/bin/gosu

FROM scratch

COPY --from=postgres / /

ENV PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    LANG="en_US.utf8" \
    PG_MAJOR="16" \
    PG_VERSION="16.14" \
    PGDATA="/var/lib/postgresql/data"

VOLUME ["/var/lib/postgresql/data"]
EXPOSE 5432
STOPSIGNAL SIGINT
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
