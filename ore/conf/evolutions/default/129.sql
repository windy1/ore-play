# --- !Ups

DROP MATERIALIZED VIEW home_projects;

-- Version downloads

CREATE TABLE project_versions_downloads_individual
(
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    project_id BIGINT      NOT NULL REFERENCES projects ON DELETE CASCADE,
    version_id BIGINT      NOT NULL REFERENCES project_versions ON DELETE CASCADE,
    address    INET        NOT NULL,
    cookie     VARCHAR(36) NOT NULL,
    user_id    BIGINT      REFERENCES users ON DELETE SET NULL,
    processed  BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE (version_id, address),
    UNIQUE (version_id, cookie),
    UNIQUE (version_id, user_id)
);

INSERT INTO project_versions_downloads_individual (created_at, project_id, version_id, address, cookie, user_id)
SELECT pvd.created_at, pv.project_id, pvd.version_id, pvd.address, pvd.cookie, pvd.user_id
FROM project_versions_downloads pvd
         JOIN project_versions pv ON pvd.version_id = pv.id;

DROP TABLE project_version_downloads;

CREATE TABLE project_versions_downloads
(
    day        DATE   NOT NULL,
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    version_id BIGINT NOT NULL REFERENCES project_versions ON DELETE CASCADE,
    downloads  INT    NOT NULL,
    PRIMARY KEY (day, version_id)
);

CREATE INDEX project_versions_downloads_project_id_version_id_idx ON project_versions_downloads (project_id, version_id);

CREATE FUNCTION update_project_versions_downloads()
    LANGUAGE plpgsql AS
$$
DECLARE
    process_limit CONSTANT TIMESTAMPTZ := now() - INTERVAL '1 day';;
BEGIN

    INSERT INTO project_versions_downloads (day, project_id, version_id, downloads)
    SELECT date_trunc('day', s.created_at), s.project_id, s.version_id, count(*)
    FROM project_versions_downloads_individual s
    WHERE NOT s.processed
      AND s.created_at <= process_limit
    GROUP BY date_trunc('day', s.created_at), s.project_id, s.version_id;;

    UPDATE project_versions_downloads_individual s
    SET processed = TRUE
    WHERE processed = FALSE
      AND s.created_at <= process_limit;;

    DELETE
    FROM project_versions_downloads_individual s
    WHERE s.processed
      AND s.created_at <= now() - INTERVAL '30 days';;

    RETURN;;
END;;
$$;

CALL update_project_versions_downloads();

INSERT INTO project_versions_downloads AS pvd (day, project_id, version_id, downloads)
SELECT CURRENT_DATE - INTERVAL '1 day', pv.project_id, pv.id, pv.downloads
FROM project_versions pv
ON CONFLICT DO UPDATE SET downloads = excluded.downloads + pvd.downloads;

-- Project views

CREATE TABLE project_views_individual
(
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    project_id BIGINT      NOT NULL REFERENCES projects ON DELETE CASCADE,
    address    INET        NOT NULL,
    cookie     VARCHAR(36) NOT NULL,
    user_id    BIGINT      REFERENCES users ON DELETE SET NULL,
    processed  BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE (project_id, address),
    UNIQUE (project_id, cookie),
    UNIQUE (project_id, user_id)
);

INSERT INTO project_views_individual (created_at, project_id, address, cookie, user_id)
SELECT pv.created_at, pv.project_id, pv.address, pv.cookie, pv.user_id
FROM project_views pv;

DROP TABLE project_views;

CREATE TABLE project_views
(
    day        DATE   NOT NULL,
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    views      INT    NOT NULL,
    PRIMARY KEY (project_id, day)
);

CREATE FUNCTION update_project_views()
    LANGUAGE plpgsql AS
$$
DECLARE
    process_limit CONSTANT TIMESTAMPTZ := now() - INTERVAL '1 day';;
BEGIN

    INSERT INTO project_views (day, project_id, views)
    SELECT date_trunc('day', s.created_at), s.project_id, count(*)
    FROM project_views_individual s
    WHERE NOT s.processed
      AND s.created_at <= process_limit
    GROUP BY date_trunc('day', s.created_at), s.project_id;;

    UPDATE project_views_individual s
    SET processed = TRUE
    WHERE processed = FALSE
      AND s.created_at <= process_limit;;

    DELETE
    FROM project_views_individual s
    WHERE s.processed
      AND s.created_at <= now() - INTERVAL '30 days';;

    RETURN;;
END;;
$$;

INSERT INTO project_views AS pv (day, project_id, views)
SELECT CURRENT_DATE - INTERVAL '1 day', p.id, p.downloads
FROM projects p
ON CONFLICT DO UPDATE SET downloads = excluded.views + pv.views;

-- Deleting old columns

ALTER TABLE projects
    DROP COLUMN views,
    DROP COLUMN downloads;

ALTER TABLE project_versions
    DROP COLUMN downloads;

DROP FUNCTION delete_old_project_views;
DROP FUNCTION delete_old_project_version_downloads;

CREATE MATERIALIZED VIEW home_projects AS
    WITH tags AS (
        SELECT sq.project_id, sq.version_string, sq.tag_name, sq.tag_version, sq.tag_color
        FROM (SELECT pv.project_id,
                     pv.version_string,
                     pvt.name                                                                            AS tag_name,
                     pvt.data                                                                            AS tag_version,
                     pvt.platform_version,
                     pvt.color                                                                           AS tag_color,
                     row_number()
                     OVER (PARTITION BY pv.project_id, pvt.platform_version ORDER BY pv.created_at DESC) AS row_num
              FROM project_versions pv
                       JOIN (
                  SELECT pvti.version_id,
                         pvti.name,
                         pvti.data,
                         --TODO, use a STORED column in Postgres 12
                         CASE
                             WHEN pvti.name = 'Sponge'
                                 THEN substring(pvti.data FROM
                                                '^\[?(\d+)\.\d+(?:\.\d+)?(?:-SNAPSHOT)?(?:-[a-z0-9]{7,9})?(?:,(?:\d+\.\d+(?:\.\d+)?)?\))?$')
                             WHEN pvti.name = 'SpongeForge'
                                 THEN substring(pvti.data FROM
                                                '^\d+\.\d+\.\d+-\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$')
                             WHEN pvti.name = 'SpongeVanilla'
                                 THEN substring(pvti.data FROM
                                                '^\d+\.\d+\.\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$')
                             WHEN pvti.name = 'Forge'
                                 THEN substring(pvti.data FROM '^\d+\.(\d+)\.\d+(?:\.\d+)?$')
                             WHEN pvti.name = 'Lantern'
                                 THEN NULL --TODO Change this once Lantern changes to SpongeVanilla's format
                             ELSE NULL
                             END AS platform_version,
                         pvti.color
                  FROM project_version_tags pvti
                  WHERE pvti.name IN ('Sponge', 'SpongeForge', 'SpongeVanilla', 'Forge', 'Lantern')
                    AND pvti.data IS NOT NULL
              ) pvt ON pv.id = pvt.version_id
              WHERE pv.visibility = 1
                AND pvt.name IN
                    ('Sponge', 'SpongeForge', 'SpongeVanilla', 'Forge', 'Lantern')
                AND pvt.platform_version IS NOT NULL) sq
        WHERE sq.row_num = 1
        ORDER BY sq.platform_version DESC)
    SELECT p.id,
           p.owner_name                      AS owner_name,
           array_agg(DISTINCT pm.user_id)    AS project_members,
           p.slug,
           p.visibility,
           coalesce(pva.views, 0)            AS views,
           coalesce(pda.downloads, 0)        AS downloads,
           coalesce(pvr.recent_views, 0)     AS recent_views,
           coalesce(pdr.recent_downloads, 0) AS recent_downloads,
           coalesce(ps.stars, 0)             AS stars,
           coalesce(pw.watchers, 0)          AS watchers,
           p.category,
           p.description,
           p.name,
           p.plugin_id,
           p.created_at,
           max(lv.created_at)                AS last_updated,
           to_jsonb(
                   ARRAY(SELECT jsonb_build_object('version_string', tags.version_string, 'tag_name',
                                                   tags.tag_name,
                                                   'tag_version', tags.tag_version, 'tag_color',
                                                   tags.tag_color)
                         FROM tags
                         WHERE tags.project_id = p.id
                         LIMIT 5))           AS promoted_versions,
           setweight(to_tsvector('english', p.name) ||
                     to_tsvector('english', regexp_replace(p.name, '([a-z])([A-Z]+)', '\1_\2', 'g')) ||
                     to_tsvector('english', p.plugin_id), 'A') ||
           setweight(to_tsvector('english', p.description), 'B') ||
           setweight(to_tsvector('english', array_to_string(p.keywords, ' ')), 'C') ||
           setweight(to_tsvector('english', p.owner_name) ||
                     to_tsvector('english', regexp_replace(p.owner_name, '([a-z])([A-Z]+)', '\1_\2', 'g')),
                     'D')                    AS search_words
    FROM projects p
             LEFT JOIN project_versions lv ON p.id = lv.project_id
             JOIN project_members_all pm ON p.id = pm.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS stars
                        FROM projects p
                                 LEFT JOIN project_stars ps ON p.id = ps.project_id
                        GROUP BY p.id) ps ON p.id = ps.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS watchers
                        FROM projects p
                                 LEFT JOIN project_watchers pw ON p.id = pw.project_id
                        GROUP BY p.id) pw ON p.id = pw.id
             LEFT JOIN (SELECT pv.project_id, sum(pv.views) AS views FROM project_views pv GROUP BY pv.project_id) pva
                       ON p.id = pva.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.downloads) AS downloads
                        FROM project_versions_downloads pv
                        GROUP BY pv.project_id) pda ON p.id = pda.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.views) AS recent_views
                        FROM project_views pv
                        GROUP BY pv.project_id) pvr
                       ON p.id = pvr.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.downloads) AS recent_downloads
                        FROM project_versions_downloads pv
                        GROUP BY pv.project_id) pdr ON p.id = pdr.project_id
    GROUP BY p.id, ps.stars, pw.watchers;


# --- !Downs

DROP MATERIALIZED VIEW home_projects;

--Adding old columns

ALTER TABLE projects
    ADD COLUMN views BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN downloads BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT projects_downloads_check CHECK ( downloads >= 0 ),
    ADD CONSTRAINT projects_views_check CHECK ( views >= 0 );

ALTER TABLE project_versions
    ADD COLUMN downloads BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT versions_downloads_check CHECK ( downloads >= 0 );

ALTER TABLE projects
    ALTER COLUMN views DROP DEFAULT,
    ALTER COLUMN downloads DROP DEFAULT;

ALTER TABLE project_versions
    ALTER COLUMN downloads DROP DEFAULT;

-- Project views

UPDATE projects p
SET views = sum(pv.views)
FROM project_views pv
WHERE p.id = pv.project_id;

DROP FUNCTION update_project_views;

DROP TABLE project_views;

CREATE TABLE project_views
(
    id         BIGSERIAL   NOT NULL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    project_id BIGINT      NOT NULL,
    address    INET        NOT NULL,
    cookie     VARCHAR(36) NOT NULL,
    user_id    BIGINT      REFERENCES users ON DELETE SET NULL,
    UNIQUE (project_id, cookie),
    UNIQUE (project_id, user_id)
);

CREATE FUNCTION delete_old_project_views() RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    DELETE FROM project_views WHERE created_at < current_date - INTERVAL '30' DAY;;
    RETURN new;;
END
$$;

CREATE TRIGGER clean_old_project_views
    AFTER INSERT
    ON project_views
EXECUTE PROCEDURE delete_old_project_views();

INSERT INTO project_views (created_at, project_id, address, cookie, user_id)
SELECT pvi.created_at, pvi.project_id, pvi.address, pvi.cookie, pvi.user_id
FROM project_views_individual pvi;

DROP TABLE project_views_individual;

-- Version downloads

UPDATE projects p
SET downloads = sum(pvd.downloads)
FROM project_versions_downloads pvd
WHERE p.id = pvd.project_id;

UPDATE project_versions pv
SET downloads = sum(pvd.downloads)
FROM project_versions_downloads pvd
WHERE pvd.project_id = pv.project_id
  AND pvd.version_id = pv.id;

DROP FUNCTION update_project_versions_downloads;

DROP TABLE project_versions_downloads;

CREATE TABLE project_version_downloads
(
    id         BIGSERIAL   NOT NULL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    version_id BIGINT      NOT NULL REFERENCES project_versions ON DELETE CASCADE,
    address    INET        NOT NULL,
    cookie     VARCHAR(36) NOT NULL,
    user_id    BIGINT REFERENCES users ON DELETE CASCADE,
    UNIQUE (version_id, address),
    UNIQUE (version_id, cookie),
    UNIQUE (version_id, user_id)
);

CREATE FUNCTION delete_old_project_version_downloads() RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    DELETE FROM project_version_downloads WHERE created_at < current_date - INTERVAL '30' DAY;;
    RETURN new;;
END
$$;

CREATE TRIGGER clean_old_project_version_downloads
    AFTER INSERT
    ON project_version_downloads
EXECUTE PROCEDURE delete_old_project_version_downloads();

CREATE MATERIALIZED VIEW home_projects AS
    WITH tags AS (
        SELECT sq.project_id, sq.version_string, sq.tag_name, sq.tag_version, sq.tag_color
        FROM (SELECT pv.project_id,
                     pv.version_string,
                     pvt.name                                                                            AS tag_name,
                     pvt.data                                                                            AS tag_version,
                     pvt.platform_version,
                     pvt.color                                                                           AS tag_color,
                     row_number()
                     OVER (PARTITION BY pv.project_id, pvt.platform_version ORDER BY pv.created_at DESC) AS row_num
              FROM project_versions pv
                       JOIN (
                  SELECT pvti.version_id,
                         pvti.name,
                         pvti.data,
                         --TODO, use a STORED column in Postgres 12
                         CASE
                             WHEN pvti.name = 'Sponge'
                                 THEN substring(pvti.data FROM
                                                '^\[?(\d+)\.\d+(?:\.\d+)?(?:-SNAPSHOT)?(?:-[a-z0-9]{7,9})?(?:,(?:\d+\.\d+(?:\.\d+)?)?\))?$')
                             WHEN pvti.name = 'SpongeForge'
                                 THEN substring(pvti.data FROM
                                                '^\d+\.\d+\.\d+-\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$')
                             WHEN pvti.name = 'SpongeVanilla'
                                 THEN substring(pvti.data FROM
                                                '^\d+\.\d+\.\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$')
                             WHEN pvti.name = 'Forge'
                                 THEN substring(pvti.data FROM '^\d+\.(\d+)\.\d+(?:\.\d+)?$')
                             WHEN pvti.name = 'Lantern'
                                 THEN NULL --TODO Change this once Lantern changes to SpongeVanilla's format
                             ELSE NULL
                             END AS platform_version,
                         pvti.color
                  FROM project_version_tags pvti
                  WHERE pvti.name IN ('Sponge', 'SpongeForge', 'SpongeVanilla', 'Forge', 'Lantern')
                    AND pvti.data IS NOT NULL
              ) pvt ON pv.id = pvt.version_id
              WHERE pv.visibility = 1
                AND pvt.name IN
                    ('Sponge', 'SpongeForge', 'SpongeVanilla', 'Forge', 'Lantern')
                AND pvt.platform_version IS NOT NULL) sq
        WHERE sq.row_num = 1
        ORDER BY sq.platform_version DESC)
    SELECT p.id,
           p.owner_name                   AS owner_name,
           array_agg(DISTINCT pm.user_id) AS project_members,
           p.slug,
           p.visibility,
           p.views,
           p.downloads,
           coalesce(ps.stars, 0)          AS stars,
           coalesce(pw.watchers, 0)       AS watchers,
           p.category,
           p.description,
           p.name,
           p.plugin_id,
           p.created_at,
           max(lv.created_at)             AS last_updated,
           to_jsonb(
                   ARRAY(SELECT jsonb_build_object('version_string', tags.version_string, 'tag_name',
                                                   tags.tag_name,
                                                   'tag_version', tags.tag_version, 'tag_color',
                                                   tags.tag_color)
                         FROM tags
                         WHERE tags.project_id = p.id
                         LIMIT 5))        AS promoted_versions,
           setweight(to_tsvector('english', p.name) ||
                     to_tsvector('english', regexp_replace(p.name, '([a-z])([A-Z]+)', '\1_\2', 'g')) ||
                     to_tsvector('english', p.plugin_id), 'A') ||
           setweight(to_tsvector('english', p.description), 'B') ||
           setweight(to_tsvector('english', array_to_string(p.keywords, ' ')), 'C') ||
           setweight(to_tsvector('english', p.owner_name) ||
                     to_tsvector('english', regexp_replace(p.owner_name, '([a-z])([A-Z]+)', '\1_\2', 'g')),
                     'D')                 AS search_words
    FROM projects p
             LEFT JOIN project_versions lv ON p.id = lv.project_id
             JOIN project_members_all pm ON p.id = pm.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS stars
                        FROM projects p
                                 LEFT JOIN project_stars ps ON p.id = ps.project_id
                        GROUP BY p.id) ps ON p.id = ps.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS watchers
                        FROM projects p
                                 LEFT JOIN project_watchers pw ON p.id = pw.project_id
                        GROUP BY p.id) pw ON p.id = pw.id
    GROUP BY p.id, ps.stars, pw.watchers;
