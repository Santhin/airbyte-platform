/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.db.instance.jobs;

import io.airbyte.db.Database;
import io.airbyte.db.instance.FlywayDatabaseMigrator;
import org.flywaydb.core.Flyway;

/**
 * Migrator for jobs db.
 */
public class JobsDatabaseMigrator extends FlywayDatabaseMigrator {

  public static final String DB_IDENTIFIER = "jobs";
  public static final String MIGRATION_FILE_LOCATION = "classpath:io/airbyte/db/instance/jobs/migrations";

  public JobsDatabaseMigrator(final Database database, final Flyway flyway) {
    super(database, flyway);
  }

}
