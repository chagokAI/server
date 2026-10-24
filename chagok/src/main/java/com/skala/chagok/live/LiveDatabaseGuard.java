package com.skala.chagok.live;

import java.net.URI;
import java.util.Set;

/** 원격 라이브 쓰기는 운영자가 명시적으로 허용해야 한다. URL을 통한 접속 대상 재지정은 허용하지 않는다. */
public final class LiveDatabaseGuard {
    private LiveDatabaseGuard() {}

    public static void requireAllowed(String jdbcUrl, boolean allowRemote, String feature) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            var uri = URI.create(jdbcUrl.substring(5));
            // pgjdbc는 query의 PGHOST·host·service·socketFactory 등으로 접속 대상을 다시 지정할 수 있다.
            // 승인한 DB_URL과 드라이버의 실제 접속 대상이 달라지지 않게 옵션 없는 단일 호스트만 받는다.
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            String host = uri.getHost();
            boolean loopback = host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                    .contains(host.toLowerCase(java.util.Locale.ROOT));
            if (host == null || (!loopback && !allowRemote) || uri.getUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(feature + " requires a single PostgreSQL host; remote access needs LIVE_ALLOW_REMOTE_DATABASE=true");
        }
    }
}
