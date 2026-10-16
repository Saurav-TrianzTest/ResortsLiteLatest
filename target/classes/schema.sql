-- ResortsLite schema — H2 in-memory database initialisation
-- Created for Spring Boot 3.x / Java 21 compatibility
-- spring.sql.init.mode=always triggers this on every startup

CREATE TABLE IF NOT EXISTS bookings (
    id       VARCHAR(50)  NOT NULL PRIMARY KEY,
    guest    VARCHAR(255) NOT NULL,
    room     VARCHAR(50)  NOT NULL,
    checkin  VARCHAR(20)  NOT NULL,
    checkout VARCHAR(20)  NOT NULL
);
