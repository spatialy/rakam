package org.rakam.realtime;

import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.QualifiedName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.inject.Singleton;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.plugin.ContinuousQuery;
import org.rakam.plugin.ContinuousQueryService;
import org.rakam.report.QueryExecutor;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;
import org.rakam.util.JsonResponse;
import org.rakam.util.NotImplementedException;
import org.rakam.util.RakamException;

import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.rakam.util.JsonHelper.convert;

@Singleton
@Api(value = "/realtime", description = "Realtime module", tags = "realtime",
        authorizations = @Authorization(value = "read_key"))
@Path("/realtime")
public class RealTimeHttpService extends HttpService {
    private final ContinuousQueryService service;
    private final QueryExecutor executor;
    private final SqlParser sqlParser = new SqlParser();
    private final Duration slideInterval = Duration.ofSeconds(5);
    private final Duration window = Duration.ofSeconds(45);

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    @Inject
    public RealTimeHttpService(ContinuousQueryService service, QueryExecutor executor) {
        this.service = requireNonNull(service, "service is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /**
     * Creates real-time report using continuous queries.
     * This module adds a new attribute called 'time' to events, it's simply a unix epoch that represents the seconds the event is occurred.
     * Continuous query continuously aggregates 'time' column and
     * real-time module executes queries on continuous query table similar to 'select count from stream_count where time &gt; now() - interval 5 second'
     *
     * curl 'http://localhost:9999/realtime/create' -H 'Content-Type: application/json;charset=UTF-8' --data-binary '{"project": "projectId", "name": "Events by collection", "aggregation": "COUNT"}'
     *
     * @param report real-time report
     * @return a future that contains the operation status
     */
    @JsonRequest
    @ApiOperation(value = "Create report")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.")})
    @Path("/create")
    public CompletableFuture<JsonResponse> create(@ParamBody RealTimeReport report) {
        String tableName = toSlug(report.name);

        String sqlQuery = new StringBuilder().append("select ")
                .append(format("(to_timestamp(_time) / %d) as _time, ", slideInterval.toMillis()))
                .append(createSelect(report.aggregation, report.measure, report.dimensions))
                .append(" from stream ")
                .append(report.filter == null ? "" : " where " + report.filter)
                .append(" group by 1 ")
                .append(report.dimensions != null ?
                        IntStream.range(0, report.dimensions.size()).mapToObj(i -> ", " + (i + 2)).collect(Collectors.joining("")) : "")
                .toString();

        ContinuousQuery query = new ContinuousQuery(report.project,
                report.name,
                tableName,
                sqlQuery,
                ImmutableList.of(),
                ImmutableMap.of("type", "realtime", "report", report));
        return service.create(query).thenApply(JsonResponse::map);
    }

    @JsonRequest
    @POST
    @ApiOperation(value = "Get report")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist."),
            @ApiResponse(code = 400, message = "Report does not exist.")})
    @Path("/get")
    public CompletableFuture<Object> get(@ApiParam(name = "project", required = true) String project,
                                         @ApiParam(name = "table_name", required = true) String tableName,
                                         @ApiParam(name = "filter", required = false) String filter,
                                         @ApiParam(name = "aggregation") AggregationType aggregation,
                                         @ApiParam(name = "measure", required = false) String measure,
                                         @ApiParam(name = "dimensions", required = false) List<String> dimensions,
                                         @ApiParam(name = "aggregate", required = false) boolean aggregate,
                                         @ApiParam(name = "date_start", required = false) Instant dateStart,
                                         @ApiParam(name = "date_end", required = false) Instant dateEnd) {
        Expression expression;
        if (filter != null) {
            expression = sqlParser.createExpression(filter);
        } else {
            expression = null;
        }

        boolean noDimension = dimensions == null || dimensions.size() == 0;

        ContinuousQuery continuousQuery = service.get(project, tableName);
        if (continuousQuery == null) {
            CompletableFuture<Object> f = new CompletableFuture<>();
            f.completeExceptionally(new RakamException("Couldn't found rule", HttpResponseStatus.BAD_REQUEST));
            return f;
        }

        long last_update = Instant.now().getEpochSecond();
        long previousWindow = (dateStart == null ? (last_update - window.getSeconds()) : dateStart.getEpochSecond()) / 5;
        long currentWindow = (dateEnd == null ? last_update : dateEnd.getEpochSecond()) / 5;

        Object timeCol = aggregate ? currentWindow : "_time";
        String sqlQuery = format("select %s, %s %s(%s) from %s where %s %s %s ORDER BY 1 ASC LIMIT 5000",
                timeCol,
                !noDimension ? dimensions.stream().collect(Collectors.joining(", ")) + "," : "",
                aggregation != null ? mapFunction(aggregation) : "",
                measure == null ? "*" : measure,
                executor.formatTableReference(continuousQuery.project, QualifiedName.of("continuous", continuousQuery.tableName)),
                format("_time >= %d", previousWindow) +
                        (dateEnd == null ? "" :
                                format("AND _time <", format("_time >= %d AND _time <= %d", previousWindow, currentWindow))),
                !noDimension || !aggregate ? String.format("GROUP BY %s %s %s", !aggregate ? timeCol : "", !aggregate && !noDimension ? "," : "", dimensions.stream().collect(Collectors.joining(", "))) : "",
                expression == null ? "" : ExpressionFormatter.formatExpression(expression));

        return executor.executeRawQuery(sqlQuery).getResult().thenApply(result -> {
            if (result.isFailed()) {
                return JsonResponse.error("Error running query: " + sqlQuery);
            }

            long previousTimestamp = previousWindow * 5;
            long currentTimestamp = currentWindow * 5;

            List<List<Object>> data = result.getResult();

            if (!aggregate) {
                if (noDimension) {
                    List<List<Object>> newData = Lists.newLinkedList();
                    int currentDataIdx = 0;
                    for (long current = previousWindow; current < currentWindow; current++) {
                        if (data.size() > currentDataIdx) {
                            List<Object> objects = data.get(currentDataIdx++);
                            Long time = ((Number) objects.get(0)).longValue();
                            if (time == current) {
                                newData.add(ImmutableList.of(current * 5, objects.get(1)));
                                continue;
                            }
                        }
                        newData.add(ImmutableList.of(current * 5, 0));
                    }
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, newData);
                } else {
                    Map<Object, List<Object>> newData = data.stream()
                            .collect(Collectors.groupingBy(entry -> (Function<List<Object>, Object>) list -> list.get(0),
                                    Collectors.mapping(l -> ImmutableList.of(l.get(1), l.get(2)), Collectors.toList())));
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, newData);
                }
            } else {
                if (noDimension) {
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, data.size() > 0 ? data.get(0).get(1) : 0);
                } else {
                    List<ImmutableList<Object>> newData = data.stream()
                            .map(m -> ImmutableList.of(m.get(1), m.get(2)))
                            .collect(Collectors.toList());
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, newData);
                }
            }
        });
    }

    public static class RealTimeQueryResult {
        public final long start;
        public final long end;
        public final Object result;

        public RealTimeQueryResult(long start, long end, Object result) {
            this.start = start;
            this.end = end;
            this.result = result;
        }
    }

    @JsonRequest
    @ApiOperation(value = "List reports")
    @Path("/list")
    public List<RealTimeReport> listReports(@ApiParam(name = "project", required = true) String project) {
        if (project == null) {
            throw new RakamException("project parameter is required", HttpResponseStatus.BAD_REQUEST);
        }
        return service.list(project).stream()
                .filter(report -> report.options != null && Objects.equals(report.options.get("type"), "realtime"))
                .map(report -> convert(report.options.get("report"), RealTimeReport.class))
                .collect(Collectors.toList());
    }

    @JsonRequest
    @ApiOperation(value = "Delete report")
    @Path("/delete")
    public CompletableFuture<JsonResponse> delete(@ApiParam(name = "project", required = true) String project,
                                                  @ApiParam(name = "name", required = true) String tableName) {

        // TODO: Check if it's a real-time report.
        return service.delete(project, tableName).thenApply(result -> {
            if (result) {
                return JsonResponse.success();
            } else {
                return JsonResponse.error("Couldn't delete report. Most probably it doesn't exist");
            }
        });

    }

    public String createSelect(AggregationType aggType, String measure, List<String> dimensions) {

        if (measure == null) {
            if (aggType != AggregationType.COUNT)
                throw new IllegalArgumentException("either measure.expression or measure.field must be specified.");
        }

        StringBuilder builder = new StringBuilder();
        if (dimensions != null)
            builder.append(" " + dimensions.stream().collect(Collectors.joining(", ")) + ", ");

        switch (aggType) {
            case AVERAGE:
                return builder.append("avg(1) as value").toString();
            case MAXIMUM:
                return builder.append("max(1) as value").toString();
            case MINIMUM:
                return builder.append("min(1) as value").toString();
            case COUNT:
                return builder.append("count(1) as value").toString();
            case SUM:
                return builder.append("sum(1) as value").toString();
            case APPROXIMATE_UNIQUE:
                return builder.append("approx_distinct(1) as value").toString();
            case POPULATION_VARIANCE:
                return builder.append("variance(1) as value").toString();
            case STANDARD_DEVIATION:
                return builder.append("stddev(1) as value").toString();
            default:
                throw new IllegalArgumentException("aggregation type couldn't found.");
        }
    }

    public String mapFunction(AggregationType aggregationType) {
        switch (aggregationType) {
            case COUNT:
            case SUM:
                return aggregationType.value();
            case MINIMUM:
                return "min";
            case MAXIMUM:
                return "max";
            case AVERAGE:
                return "avg";
            case STANDARD_DEVIATION:
                return "stddev";
            case POPULATION_VARIANCE:
                return "variance";
            case APPROXIMATE_UNIQUE:
                return "approx_distinct";
            default:
                throw new NotImplementedException();
        }
    }

    /*
     * Taken from http://stackoverflow.com/a/1657250/689144
     */
    public static String toSlug(String input) {
        String nowhitespace = WHITESPACE.matcher(input).replaceAll("_");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        return slug.toLowerCase(Locale.ENGLISH);
    }
}
