CREATE TABLE daily_installations (
  day TEXT NOT NULL,
  installation TEXT NOT NULL,
  PRIMARY KEY (day, installation)
) WITHOUT ROWID;

CREATE TABLE accepted_starts (
  event_id TEXT PRIMARY KEY NOT NULL,
  day TEXT NOT NULL
) WITHOUT ROWID;

CREATE INDEX accepted_starts_day ON accepted_starts (day);
