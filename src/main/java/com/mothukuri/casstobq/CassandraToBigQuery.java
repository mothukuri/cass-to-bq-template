package com.mothukuri.casstobq;

import com.datastax.driver.core.Session;

import java.util.Arrays;
import java.util.List;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.cassandra.CassandraIO;
import org.apache.beam.sdk.io.cassandra.Mapper;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.Row;

public class CassandraToBigQuery {

  public interface Options extends PipelineOptions {

    // @TemplateParameter.Text(
    //     order = 1,
    //     regexes = {"^[a-zA-Z0-9\\.\\-,]*$"},
    //     description = "Cassandra Hosts",
    //     helpText = "Comma separated value list of hostnames or ips of the Cassandra nodes.")
    ValueProvider<String> getCassandraHosts();    

    @SuppressWarnings("unused")
    void setCassandraHosts(ValueProvider<String> hosts);

    // @TemplateParameter.Text(
    //     order = 2,
    //     optional = true,
    //     regexes = {
    //       "^([0-9]{1,4}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$"
    //     },
    //     description = "Cassandra Port",
    //     helpText = "The port where cassandra can be reached. Defaults to 9042.")
    @Default.Integer(9042)
    ValueProvider<Integer> getCassandraPort();

    @SuppressWarnings("unused")
    void setCassandraPort(ValueProvider<Integer> port);

    // @TemplateParameter.Text(
    //     order = 3,
    //     regexes = {"^[a-zA-Z0-9][a-zA-Z0-9_]{0,47}$"},
    //     description = "Cassandra Keyspace",
    //     helpText = "Cassandra Keyspace where the table to be migrated can be located.")
    ValueProvider<String> getCassandraKeyspace();

    @SuppressWarnings("unused")
    void setCassandraKeyspace(ValueProvider<String> keyspace);

    // @TemplateParameter.Text(
    //     order = 4,
    //     regexes = {"^[a-zA-Z][a-zA-Z0-9_]*$"},
    //     description = "Cassandra Table",
    //     helpText = "The name of the Cassandra table to Migrate")
    ValueProvider<String> getCassandraTable();

    @SuppressWarnings("unused")
    void setCassandraTable(ValueProvider<String> cassandraTable);

    // @TemplateParameter.ProjectId(
    //     order = 5,
    //     description = "Bigtable Project ID",
    //     helpText = "The Project ID where the target Bigtable Instance is running.")
    ValueProvider<String> getBigtableProjectId();

    @SuppressWarnings("unused")
    void setBigtableProjectId(ValueProvider<String> projectId);

    // @TemplateParameter.Text(
    //     order = 6,
    //     regexes = {"[a-z][a-z0-9\\-]+[a-z0-9]"},
    //     description = "Target Bigtable Instance",
    //     helpText = "The target Bigtable Instance where you want to write the data.")
    ValueProvider<String> getBigtableInstanceId();

    @SuppressWarnings("unused")
    void setBigtableInstanceId(ValueProvider<String> bigtableInstanceId);

    // @TemplateParameter.Text(
    //     order = 7,
    //     regexes = {"[_a-zA-Z0-9][-_.a-zA-Z0-9]*"},
    //     description = "Target Bigtable Table",
    //     helpText = "The target Bigtable table where you want to write the data.")
    ValueProvider<String> getBigtableTableId();

    @SuppressWarnings("unused")
    void setBigtableTableId(ValueProvider<String> bigtableTableId);

    // @TemplateParameter.Text(
    //     order = 8,
    //     optional = true,
    //     regexes = {"[-_.a-zA-Z0-9]+"},
    //     description = "The Default Bigtable Column Family",
    //     helpText =
    //         "This specifies the default column family to write data into. If no columnFamilyMapping is specified all Columns will be written into this column family. Default value is \"default\"")
    @Default.String("default")
    ValueProvider<String> getDefaultColumnFamily();

    @SuppressWarnings("unused")
    void setDefaultColumnFamily(ValueProvider<String> defaultColumnFamily);

    // @TemplateParameter.Text(
    //     order = 9,
    //     optional = true,
    //     description = "The Row Key Separator",
    //     helpText =
    //         "All primary key fields will be appended to form your Bigtable Row Key. The rowKeySeparator allows you to specify a character separator. Default separator is '#'.")
    @Default.String("#")
    ValueProvider<String> getRowKeySeparator();

    @SuppressWarnings("unused")
    void setRowKeySeparator(ValueProvider<String> rowKeySeparator);

    // @TemplateParameter.Boolean(
    //     order = 10,
    //     optional = true,
    //     description = "If true, large rows will be split into multiple MutateRows requests",
    //     helpText =
    //         "The flag for enabling splitting of large rows into multiple MutateRows requests. Note that when a large row is split between multiple API calls, the updates to the row are not atomic. ")
    ValueProvider<Boolean> getSplitLargeRows();

    void setSplitLargeRows(ValueProvider<Boolean> splitLargeRows);
  }

  /**
   * Runs a pipeline to copy one Cassandra table to a Bigquery table.
   *
   */
  public static void main(String[] args) {

    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

    // Split the Cassandra Hosts value provider into a list value provider.
    ValueProvider.NestedValueProvider<List<String>, String> hosts =
        ValueProvider.NestedValueProvider.of(
            options.getCassandraHosts(),
            (SerializableFunction<String, List<String>>) value -> Arrays.asList(value.split(",")));

    Pipeline p = Pipeline.create(PipelineUtils.tweakPipelineOptions(options));

    // Create a factory method to inject the CassandraRowMapperFn to allow custom type mapping.
    SerializableFunction<Session, Mapper> cassandraObjectMapperFactory =
        new CassandraRowMapperFactory(options.getCassandraTable(), options.getCassandraKeyspace());

    CassandraIO.Read<Row> source =
        CassandraIO.<Row>read()
            .withHosts(hosts)
            .withPort(options.getCassandraPort())
            .withKeyspace(options.getCassandraKeyspace())
            .withTable(options.getCassandraTable())
            .withMapperFactoryFn(cassandraObjectMapperFactory)
            .withEntity(Row.class)
            .withCoder(SerializableCoder.of(Row.class));

    BigtableIO.Write sink =
        BigtableIO.write()
            .withProjectId(options.getBigtableProjectId())
            .withInstanceId(options.getBigtableInstanceId())
            .withTableId(options.getBigtableTableId());

    p.apply("Read from Cassandra", source)
        .apply(
            "Convert Row",
            ParDo.of(
                BeamRowToBigtableFn.createWithSplitLargeRows(
                    options.getRowKeySeparator(),
                    options.getDefaultColumnFamily(),
                    options.getSplitLargeRows(),
                    BeamRowToBigtableFn.MAX_MUTATION_PER_REQUEST)))
        .apply("Write to Bigtable", sink);
    p.run();
  }
}

