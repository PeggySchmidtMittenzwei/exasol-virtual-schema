package com.exasol.adapter.dialects.exasol;

import static com.exasol.adapter.AdapterProperties.*;
import static com.exasol.adapter.capabilities.MainCapability.*;
import static com.exasol.adapter.dialects.exasol.ExasolProperties.EXASOL_CONNECTION_PROPERTY;
import static com.exasol.adapter.dialects.exasol.ExasolProperties.EXASOL_IMPORT_PROPERTY;
import static com.exasol.adapter.sql.ScalarFunction.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.exasol.adapter.AdapterProperties;
import com.exasol.adapter.capabilities.*;
import com.exasol.adapter.dialects.*;
import com.exasol.adapter.dialects.rewriting.SqlGenerationContext;
import com.exasol.adapter.jdbc.*;
import com.exasol.adapter.properties.BooleanProperty;
import com.exasol.adapter.properties.ImportProperty;
import com.exasol.errorreporting.ExaError;

/**
 * Exasol SQL dialect.
 */
public class ExasolSqlDialect extends AbstractSqlDialect {
    static final String EXASOL_TIMESTAMP_WITH_LOCAL_TIME_ZONE_SWITCH = "TIMESTAMP_WITH_LOCAL_TIME_ZONE_USAGE";
    static final String NAME = "EXASOL";
    private static final Capabilities CAPABILITIES = createCapabilityList();

    /**
     * Create a new instance of the {@link ExasolSqlDialect}.
     *
     * @param connectionFactory factory for the JDBC connection to the remote data source
     * @param properties        adapter properties
     */
    public ExasolSqlDialect(final ConnectionFactory connectionFactory, final AdapterProperties properties) {
        super(connectionFactory, properties,
                Set.of(CATALOG_NAME_PROPERTY, SCHEMA_NAME_PROPERTY, EXASOL_IMPORT_PROPERTY, EXASOL_CONNECTION_PROPERTY,
                        IS_LOCAL_PROPERTY, IGNORE_ERRORS_PROPERTY), //
                List.of(SchemaNameProperty.validator(NAME), //
                        BooleanProperty.validator(EXASOL_IMPORT_PROPERTY), //
                        BooleanProperty.validator(IS_LOCAL_PROPERTY), //
                        ImportProperty.validator(EXASOL_IMPORT_PROPERTY, EXASOL_CONNECTION_PROPERTY)));
        this.omitParenthesesMap.addAll(Set.of(SYSDATE, SYSTIMESTAMP, CURRENT_SCHEMA, CURRENT_SESSION, CURRENT_STATEMENT,
                CURRENT_USER, CURRENT_CLUSTER));
    }

    @Override
    public String getName() {
        return NAME;
    }

    private static Capabilities createCapabilityList() {
        return Capabilities.builder() //
                .addMain(SELECTLIST_PROJECTION, SELECTLIST_EXPRESSIONS, FILTER_EXPRESSIONS, AGGREGATE_SINGLE_GROUP,
                        AGGREGATE_GROUP_BY_COLUMN, AGGREGATE_GROUP_BY_EXPRESSION, AGGREGATE_GROUP_BY_TUPLE,
                        AGGREGATE_HAVING, ORDER_BY_COLUMN, ORDER_BY_EXPRESSION, LIMIT, LIMIT_WITH_OFFSET, JOIN,
                        JOIN_TYPE_INNER, JOIN_TYPE_LEFT_OUTER, JOIN_TYPE_RIGHT_OUTER, JOIN_TYPE_FULL_OUTER,
                        JOIN_CONDITION_EQUI) //
                .addLiteral(LiteralCapability.values()) //
                .addPredicate(PredicateCapability.values()) //
                .addAggregateFunction(AggregateFunctionCapability.values()) //
                .addScalarFunction(ScalarFunctionCapability.values()) //
                .build();
    }

    @Override
    protected RemoteMetadataReader createRemoteMetadataReader() {
        try {
            return new ExasolMetadataReader(this.connectionFactory.getConnection(), this.properties);
        } catch (final SQLException exception) {
            throw new RemoteMetadataReaderException(ExaError.messageBuilder("E-VSEXA-4") //
                    .message("Unable to create Exasol remote metadata reader.").toString(), exception);
        }
    }

    /**
     * Create a query rewriter.
     * <p>
     * Virtual Schema for Exasol supports the following import variants which are represented by dedicated query
     * re-writers:
     * <dl>
     * <dt>local</dt>
     * <dd>Create a {@code SELECT} statement that is directly executed on the local Exasol database.
     * <dt>{@code IMPORT FROM EXA}</dt>
     * <dd>Create dedicated import statement for a remote Exasol database that is more efficient than a regular JDBC
     * import.</dd>
     * <dt>JDBC import</dt>
     * <dd>Create a regular JDBC import</dd>
     * </dl>
     */
    @Override
    protected QueryRewriter createQueryRewriter() {
        if (this.properties.isLocalSource()) {
            return new ExasolLocalQueryRewriter(this);
        } else if (isImportFromExa(this.properties)) {
            return new ExasolFromExaQueryRewriter(this, createRemoteMetadataReader());
        } else {
            return new ExasolJdbcQueryRewriter(this, createRemoteMetadataReader(), this.connectionFactory);
        }
    }

    private boolean isImportFromExa(final AdapterProperties properties) {
        return properties.isEnabled(EXASOL_IMPORT_PROPERTY);
    }

    @Override
    public StructureElementSupport supportsJdbcCatalogs() {
        return StructureElementSupport.SINGLE;
    }

    @Override
    public StructureElementSupport supportsJdbcSchemas() {
        return StructureElementSupport.MULTIPLE;
    }

    @Override
    public String applyQuote(final String identifier) {
        return super.quoteIdentifierWithDoubleQuotes(identifier);
    }

    @Override
    public Capabilities getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean requiresCatalogQualifiedTableNames(final SqlGenerationContext context) {
        return false;
    }

    @Override
    public boolean requiresSchemaQualifiedTableNames(final SqlGenerationContext context) {
        return true;
    }

    @Override
    public NullSorting getDefaultNullSorting() {
        return NullSorting.NULLS_SORTED_HIGH;
    }

    @Override
    public String getStringLiteral(final String value) {
        return super.quoteLiteralStringWithSingleQuote(value);
    }

    @Override
    public SqlGenerator getSqlGenerator(final SqlGenerationContext context) {
        return new ExasolSqlGenerationVisitor(this, context);
    }

    /**
     * Check if the adapter should ignore the usage of the literal timestamp with local time zone.
     *
     * @return <code>true</code> if the property is enabled
     */
    boolean isTimestampWithLocalTimeZoneEnabled() {
        return this.properties.getIgnoredErrors().contains(EXASOL_TIMESTAMP_WITH_LOCAL_TIME_ZONE_SWITCH);
    }
}
