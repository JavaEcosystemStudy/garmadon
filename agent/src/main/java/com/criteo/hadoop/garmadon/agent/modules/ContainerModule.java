package com.criteo.hadoop.garmadon.agent.modules;

import com.criteo.hadoop.garmadon.agent.AsyncEventProcessor;
import com.criteo.hadoop.garmadon.agent.headers.ContainerHeader;
import com.criteo.hadoop.garmadon.agent.tracers.FileSystemTracer;
import com.criteo.hadoop.garmadon.agent.tracers.JVMStatisticsTracer;
import com.criteo.hadoop.garmadon.agent.tracers.SparkListenerTracer;
import com.criteo.hadoop.garmadon.schema.enums.Component;
import com.criteo.hadoop.garmadon.schema.enums.Framework;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.*;

public class ContainerModule implements GarmadonAgentModule {

    @Override
    public void setup(Instrumentation instrumentation, AsyncEventProcessor eventProcessor) {
        ExecutorService executorService = Executors.newFixedThreadPool(5);
        // JVM/GC metrics/events
        executorService.submit(() -> JVMStatisticsTracer.setup(event ->
                eventProcessor.offer(ContainerHeader.getInstance().getHeader(), event)));

        // Byte code instrumentation
        executorService.submit(() -> FileSystemTracer.setup(instrumentation,
                event -> eventProcessor.offer(ContainerHeader.getInstance().getHeader(), event)));

        // Set SPARK Listener
        executorService.submit(() -> {
            ContainerHeader containerHeader = ContainerHeader.getInstance();
            if (containerHeader.getFramework().equals(Framework.SPARK) && containerHeader.getComponent().equals(Component.APP_MASTER)) {
                SparkListenerTracer.setup(event -> eventProcessor.offer(containerHeader.getHeader(), event));
            }
        });

        executorService.shutdown();
        // We wait 3 sec executor to instrument classes
        // If all classes are still not instrumented after that time we let the JVM continue startup
        // in order to not block the container for too long on agent initialization
        // Currently we are seeing avg duration of 160 s on MapRed and 6500 s on SPARK
        // so we consider 3s as a reasonable duration
        // Downside: we can have some classes not instrumenting loaded by the JVM so missing
        // some metrics on a container
        try {
            executorService.awaitTermination(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }
}
