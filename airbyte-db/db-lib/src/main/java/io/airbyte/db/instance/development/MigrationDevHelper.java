/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.db.instance.development;

import com.google.common.annotations.VisibleForTesting;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.commons.version.AirbyteVersion;
import io.airbyte.db.instance.FlywayDatabaseMigrator;
import io.micronaut.core.util.StringUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.flywaydb.core.api.ClassProvider;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.resolver.java.ScanningJavaMigrationResolver;
import org.flywaydb.core.internal.scanner.LocationScannerCache;
import org.flywaydb.core.internal.scanner.ResourceNameCache;
import org.flywaydb.core.internal.scanner.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migration helper.
 */
public class MigrationDevHelper {

  private static final Logger LOGGER = LoggerFactory.getLogger(MigrationDevHelper.class);

  public static final String AIRBYTE_VERSION_ENV_VAR = "AIRBYTE_VERSION";
  public static final String VERSION_ENV_VAR = "VERSION";

  /**
   * This method is used for migration development. Run it to see how your migration changes the
   * database schema.
   */
  public static void runLastMigration(final DevDatabaseMigrator migrator) throws IOException {
    migrator.createBaseline();

    final List<MigrationInfo> preMigrationInfoList = migrator.list();
    LOGGER.info("\n==== Pre Migration Info ====\n" + FlywayFormatter.formatMigrationInfoList(preMigrationInfoList));
    LOGGER.info("\n==== Pre Migration Schema ====\n" + migrator.dumpSchema() + "\n");

    final MigrateResult migrateResult = migrator.migrate();
    LOGGER.info("\n==== Migration Result ====\n" + FlywayFormatter.formatMigrationResult(migrateResult));

    final List<MigrationInfo> postMigrationInfoList = migrator.list();
    LOGGER.info("\n==== Post Migration Info ====\n" + FlywayFormatter.formatMigrationInfoList(postMigrationInfoList));
    LOGGER.info("\n==== Post Migration Schema ====\n" + migrator.dumpSchema() + "\n");
  }

  static void createNextMigrationFile(final String dbIdentifier, final FlywayDatabaseMigrator migrator) throws IOException {
    final String description = "New_migration";

    final MigrationVersion nextMigrationVersion = getNextMigrationVersion(migrator);
    final String versionId = nextMigrationVersion.toString().replaceAll("\\.", "_");

    final String template = MoreResources.readResource("migration_template.txt");
    final String newMigration = template.replace("<db-name>", dbIdentifier)
        .replaceAll("<version-id>", versionId)
        .replaceAll("<description>", description)
        .strip();

    final String fileName = String.format("V%s__%s.java", versionId, description);
    final String filePath = String.format("src/main/java/io/airbyte/db/instance/%s/migrations/%s", dbIdentifier, fileName);

    LOGGER.info("\n==== New Migration File ====\n" + filePath);

    final File file = new File(Path.of(filePath).toUri());
    Files.createDirectories(file.toPath().getParent());

    try (final PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
      writer.println(newMigration);
    } catch (final FileNotFoundException e) {
      throw new IOException(e);
    }
  }

  static Optional<MigrationVersion> getSecondToLastMigrationVersion(final FlywayDatabaseMigrator migrator) {
    final List<ResolvedMigration> migrations = getAllMigrations(migrator);
    if (migrations.isEmpty() || migrations.size() == 1) {
      return Optional.empty();
    }
    return Optional.of(migrations.get(migrations.size() - 2).getVersion());
  }

  /**
   * Dump schema to file.
   *
   * @param schema to dump
   * @param schemaDumpFile to dump to
   * @param printSchema should dump schema
   * @throws IOException exception while accessing database
   */
  public static void dumpSchema(final String schema, final String schemaDumpFile, final boolean printSchema) throws IOException {
    try (final PrintWriter writer = new PrintWriter(new File(Path.of(schemaDumpFile).toUri()), StandardCharsets.UTF_8)) {
      writer.println(schema);
      if (printSchema) {
        LOGGER.info("\n==== Schema ====\n" + schema);
        LOGGER.info("\n==== Dump File ====\nThe schema has been written to: " + schemaDumpFile);
      }
    } catch (final FileNotFoundException e) {
      throw new IOException(e);
    }
  }

  /**
   * This method is for migration development and testing purposes. So it is not exposed on the
   * interface. Reference: <a href=
   * "https://github.com/flyway/flyway/blob/master/flyway-core/src/main/java/org/flywaydb/core/Flyway.java#L621">Flyway.java</a>.
   */
  private static List<ResolvedMigration> getAllMigrations(final FlywayDatabaseMigrator migrator) {
    final Configuration configuration = migrator.getFlyway().getConfiguration();
    final ClassProvider<JavaMigration> scanner = new Scanner<>(
        JavaMigration.class,
        false,
        new ResourceNameCache(),
        new LocationScannerCache(),
        configuration);
    final ScanningJavaMigrationResolver resolver = new ScanningJavaMigrationResolver(scanner, configuration);
    return resolver.resolveMigrations(new MigrationResolver.Context(configuration, null, null, null, null)).stream()
        // There may be duplicated migration from the resolver.
        .distinct()
        .collect(Collectors.toList());
  }

  private static Optional<MigrationVersion> getLastMigrationVersion(final FlywayDatabaseMigrator migrator) {
    final List<ResolvedMigration> migrations = getAllMigrations(migrator);
    if (migrations.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(migrations.getLast().getVersion());
  }

  @VisibleForTesting
  static AirbyteVersion getCurrentAirbyteVersion() {
    final String airbyteVersion = System.getenv(AIRBYTE_VERSION_ENV_VAR);
    final String version = System.getenv(VERSION_ENV_VAR);
    if (StringUtils.isNotEmpty(airbyteVersion)) {
      return new AirbyteVersion(airbyteVersion);
    } else if (StringUtils.isNotEmpty(version)) {
      return new AirbyteVersion(version);
    } else {
      throw new IllegalStateException("Cannot find current Airbyte version from environment.");
    }
  }

  /**
   * Turn a migration version to airbyte version and drop the migration id. E.g. "0.29.10.004" ->
   * "0.29.10".
   */
  @VisibleForTesting
  static AirbyteVersion getAirbyteVersion(final MigrationVersion version) {
    final String[] splits = version.getVersion().split("\\.");
    return new AirbyteVersion(splits[0], splits[1], splits[2]);
  }

  /**
   * Extract the major, minor, and patch version and join them with underscore. E.g. "0.29.10-alpha"
   * -> "0_29_10",
   */
  @VisibleForTesting
  static String formatAirbyteVersion(final AirbyteVersion version) {
    return String.format("%s_%s_%s", version.getMajorVersion(), version.getMinorVersion(), version.getPatchVersion());
  }

  /**
   * Extract the migration id. E.g. "0.29.10.001" -> "001".
   */
  @VisibleForTesting
  static String getMigrationId(final MigrationVersion version) {
    return version.getVersion().split("\\.")[3];
  }

  private static MigrationVersion getNextMigrationVersion(final FlywayDatabaseMigrator migrator) {
    final Optional<MigrationVersion> lastMigrationVersion = getLastMigrationVersion(migrator);
    final AirbyteVersion currentAirbyteVersion = getCurrentAirbyteVersion();
    return getNextMigrationVersion(currentAirbyteVersion, lastMigrationVersion);
  }

  @VisibleForTesting
  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  static MigrationVersion getNextMigrationVersion(final AirbyteVersion currentAirbyteVersion, final Optional<MigrationVersion> lastMigrationVersion) {
    // When there is no migration, use the current airbyte version.
    if (lastMigrationVersion.isEmpty()) {
      LOGGER.info("No migration exists. Use the current airbyte version {}", currentAirbyteVersion);
      return MigrationVersion.fromVersion(String.format("%s_001", formatAirbyteVersion(currentAirbyteVersion)));
    }

    // When the current airbyte version is greater, use the airbyte version.
    final MigrationVersion migrationVersion = lastMigrationVersion.get();
    final AirbyteVersion migrationAirbyteVersion = getAirbyteVersion(migrationVersion);
    if (currentAirbyteVersion.versionCompareTo(migrationAirbyteVersion) > 0) {
      LOGGER.info(
          "Use the current airbyte version ({}), since it is greater than the last migration version ({})",
          currentAirbyteVersion,
          migrationAirbyteVersion);
      return MigrationVersion.fromVersion(String.format("%s_001", formatAirbyteVersion(currentAirbyteVersion)));
    }

    // When the last migration version is greater, which usually does not happen, use the migration
    // version.
    LOGGER.info(
        "Use the last migration version ({}), since it is greater than or equal to the current airbyte version ({})",
        migrationAirbyteVersion,
        currentAirbyteVersion);
    final String lastMigrationId = getMigrationId(migrationVersion);
    LOGGER.info("lastMigrationId: " + lastMigrationId);
    final String nextMigrationId = String.format("%03d", Integer.parseInt(lastMigrationId) + 1);
    LOGGER.info("nextMigrationId: " + nextMigrationId);
    return MigrationVersion.fromVersion(String.format("%s_%s", migrationAirbyteVersion.serialize(), nextMigrationId));
  }

}
