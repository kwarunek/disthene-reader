package net.iponweb.disthene.reader.handler;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.google.common.util.concurrent.TimeLimiter;
import com.google.common.util.concurrent.UncheckedTimeoutException;
import io.netty.handler.codec.http.*;
import net.iponweb.disthene.reader.beans.TimeSeries;
import net.iponweb.disthene.reader.config.ReaderConfiguration;
import net.iponweb.disthene.reader.exceptions.*;
import net.iponweb.disthene.reader.format.ResponseFormatter;
import net.iponweb.disthene.reader.graphite.Target;
import net.iponweb.disthene.reader.graphite.TargetVisitor;
import net.iponweb.disthene.reader.graphite.evaluation.EvaluationContext;
import net.iponweb.disthene.reader.graphite.evaluation.TargetEvaluator;
import net.iponweb.disthene.reader.graphite.grammar.GraphiteLexer;
import net.iponweb.disthene.reader.graphite.grammar.GraphiteParser;
import net.iponweb.disthene.reader.graphite.utils.ValueFormatter;
import net.iponweb.disthene.reader.handler.parameters.RenderParameters;
import net.iponweb.disthene.reader.service.metric.MetricService;
import net.iponweb.disthene.reader.service.stats.StatsService;
import net.iponweb.disthene.reader.service.throttling.ThrottlingService;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author Andrei Ivanov
 */
public class RenderHandler implements DistheneReaderHandler {

    final static Logger logger = Logger.getLogger(RenderHandler.class);

    private TargetEvaluator evaluator;
    private StatsService statsService;
    private ThrottlingService throttlingService;
    private ReaderConfiguration readerConfiguration;

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private TimeLimiter timeLimiter = new SimpleTimeLimiter(executor);


    public RenderHandler(MetricService metricService, StatsService statsService, ThrottlingService throttlingService, ReaderConfiguration readerConfiguration) {
        this.evaluator = new TargetEvaluator(metricService);
        this.statsService = statsService;
        this.throttlingService = throttlingService;
        this.readerConfiguration = readerConfiguration;
    }

    @Override
    public FullHttpResponse handle(final HttpRequest request) throws ParameterParsingException, ExecutionException, InterruptedException, EvaluationException, LogarithmicScaleNotAllowed {
        final RenderParameters parameters = RenderParameters.parse(request);

        logger.debug("Got request: " + parameters);
        Stopwatch timer = Stopwatch.createStarted();

        double throttled = throttlingService.throttle(parameters.getTenant());

        statsService.incRenderRequests(parameters.getTenant());

        if (throttled > 0) {
            statsService.incThrottleTime(parameters.getTenant(), throttled);
        }

        final List<Target> targets = new ArrayList<>();

        EvaluationContext context = new EvaluationContext(
                readerConfiguration.isHumanReadableNumbers() ? ValueFormatter.getInstance(parameters.getFormat()) : ValueFormatter.getInstance(ValueFormatter.ValueFormatterType.MACHINE)
        );

        // Let's parse the targets
        for(String targetString : parameters.getTargets()) {
            GraphiteLexer lexer = new GraphiteLexer(new ANTLRInputStream(targetString));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            GraphiteParser parser = new GraphiteParser(tokens);
            ParseTree tree = parser.expression();
            try {
                targets.add(new TargetVisitor(parameters.getTenant(), parameters.getFrom(), parameters.getUntil(), context).visit(tree));
            } catch (ParseCancellationException e) {
                String additionalInfo = null;
                if (e.getMessage() != null) additionalInfo = e.getMessage();
                if (e.getCause() != null) additionalInfo = e.getCause().getMessage();
                throw new InvalidParameterValueException("Could not parse target: " + targetString + " (" + additionalInfo + ")");
            }
        }

        FullHttpResponse response;
        try {
            response = timeLimiter.callWithTimeout(new Callable<FullHttpResponse>() {
                @Override
                public FullHttpResponse call() throws EvaluationException, LogarithmicScaleNotAllowed {
                    return handleInternal(targets, parameters);
                }
            }, readerConfiguration.getRequestTimeout(), TimeUnit.SECONDS, true);
        } catch (UncheckedTimeoutException e) {
            logger.debug("Request timed out: " + parameters);
            statsService.incTimedOutRequests(parameters.getTenant());
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
        } catch (EvaluationException | LogarithmicScaleNotAllowed e) {
            // TODO: distinguish the bad request (bad params, non-existent function,.. ) from internal error
            // e.printStackTrace(System.out);
            throw e;
        } catch (Exception e) {
            // TODO: distinguish the bad request (bad params, non-existent function,.. ) from internal error
            // e.printStackTrace(System.out);
            logger.error(e);
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }

        timer.stop();
        logger.debug("Request took " + timer.elapsed(TimeUnit.MILLISECONDS) + " milliseconds (" + parameters + ")");

        return response;
    }
    private FullHttpResponse handleInternal(List<Target> targets, RenderParameters parameters) throws EvaluationException, LogarithmicScaleNotAllowed {
        // now evaluate each target producing list of TimeSeries
        List<TimeSeries> results = new ArrayList<>();

        for(Target target : targets) {
            results.addAll(evaluator.eval(target));
        }

        return ResponseFormatter.formatResponse(results, parameters);
    }

}
