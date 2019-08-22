package com.criteo.hadoop.garmadon.hdfs.writer;

import akka.Done;
import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import akka.japi.pf.FI;
import com.criteo.hadoop.garmadon.reader.Offset;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static akka.pattern.Patterns.ask;

public class AsyncPartitionedWriter<M> {

    private static final Duration MAX_AKKA_DELAY = Duration.ofSeconds(21474835);

    private final ActorRef actor;

    AsyncPartitionedWriter(ActorRef actor) {
        this.actor = actor;
    }

    public static <M> AsyncPartitionedWriter<M> create(ActorSystem system, PartitionedWriter<M> writer) {
        return new AsyncPartitionedWriter<>(system.actorOf(props(writer)));
    }

    private static <M> Props props(PartitionedWriter<M> writer) {
        return Props.create(AsyncPartitionedWriter.Actor.class, () -> new Actor((PartitionedWriter<Object>) writer));
    }

    public CompletableFuture<Done> close() {
        return ask(actor, new Actor.CloseEvent(), MAX_AKKA_DELAY).toCompletableFuture().thenApply(o -> (Done) o);
    }

    public CompletableFuture<Done> write(Instant when, Offset offset, Supplier<M> msg) {
        return ask(actor, new Actor.WriteEvent(when, offset, msg), MAX_AKKA_DELAY).toCompletableFuture().thenApply(o -> (Done) o);
    }

    public CompletableFuture<Done> dropPartition(int partition) {
        return ask(actor, new Actor.DropPartitionEvent(partition), MAX_AKKA_DELAY).toCompletableFuture().thenApply(o -> (Done) o);
    }

    public CompletableFuture<Done> expireConsumers() {
        return ask(actor, new Actor.ExpireConsumersEvent(), MAX_AKKA_DELAY).toCompletableFuture().thenApply(o -> (Done) o);
    }

    public CompletableFuture<Map<Integer, Long>> getStartingOffsets(Collection<Integer> partitions) {
        return ask(actor, new Actor.GetStartingOffsetsEvent(partitions), MAX_AKKA_DELAY).toCompletableFuture().thenApply(o -> (Map<Integer, Long>) o);
    }

    public CompletableFuture<Done> heartbeat(int partition, Offset offset) {
        return ask(actor, new Actor.HeartbeatEvent(partition, offset), MAX_AKKA_DELAY).toCompletableFuture().thenApply(o -> (Done) o);
    }

    static class Actor extends AbstractActor {

        private static SupervisorStrategy strategy = new OneForOneStrategy(
            DeciderBuilder
                .match(Exception.class, e -> SupervisorStrategy.resume())
                .build()
        );

        private final PartitionedWriter<Object> writer;

        Actor(PartitionedWriter<Object> writer) {
            this.writer = writer;
        }

        @Override
        public SupervisorStrategy supervisorStrategy() {
            return strategy;
        }

        @Override
        public AbstractActor.Receive createReceive() {
            return receiveBuilder()
                .match(CloseEvent.class, protect(evt -> {
                    doClose();
                    done();
                }))
                .match(DropPartitionEvent.class, protect(evt -> {
                    doDropPartition(evt.partition);
                    done();
                }))
                .match(ExpireConsumersEvent.class, protect(evt -> {
                    doExpireConsumers();
                    done();
                }))
                .match(GetStartingOffsetsEvent.class, protect(evt -> {
                    reply(doGetStartingOffsets(evt.partitions));
                }))
                .match(HeartbeatEvent.class, protect(evt -> {
                    doHeartbeat(evt.partition, evt.offset);
                    done();
                }))
                .match(WriteEvent.class, protect(evt -> {
                    doWrite(evt.when, evt.offset, evt.msgSupplier.get());
                    done();
                }))
                .build();
        }

        private void doClose() {
            writer.close();
        }

        private void doDropPartition(int p) {
            writer.dropPartition(p);
        }

        private void doExpireConsumers() {
            writer.expireConsumers();
        }

        private Map<Integer, Long> doGetStartingOffsets(Collection<Integer> partitions) throws IOException {
            return writer.getStartingOffsets(partitions);
        }

        private void doHeartbeat(int p, Offset o) {
            writer.heartbeat(p, o);
        }

        private void doWrite(Instant when, Offset offset, Object msg) throws IOException {
            writer.write(when, offset, msg);
        }

        private void done() {
            getSender().tell(Done.getInstance(), getSelf());
        }

        private void reply(Object o) {
            getSender().tell(o, getSelf());
        }

        private <P> FI.UnitApply<P> protect(FI.UnitApply<P> action) {
            return p -> {
                try {
                    action.apply(p);
                } catch (Exception e) {
                    replyFailure(e);
                }
            };
        }

        private void replyFailure(Exception e) {
            getSender().tell(new Status.Failure(e), getSelf());
        }

        /* Events the actor can receive */
        static class WriteEvent {

            private final Instant when;
            private final Supplier msgSupplier;
            private final Offset offset;

            WriteEvent(Instant when, Offset offset, Supplier msgSupplier) {
                this.when = when;
                this.offset = offset;
                this.msgSupplier = msgSupplier;
            }
        }

        static class CloseEvent {
        }

        static class DropPartitionEvent {

            private final int partition;

            DropPartitionEvent(int partition) {
                this.partition = partition;
            }
        }

        static class ExpireConsumersEvent {

        }

        static class GetStartingOffsetsEvent {

            private final Collection<Integer> partitions;

            GetStartingOffsetsEvent(Collection<Integer> partitions) {
                this.partitions = partitions;
            }
        }

        static class HeartbeatEvent {

            private final int partition;
            private final Offset offset;

            HeartbeatEvent(int partition, Offset offset) {
                this.partition = partition;
                this.offset = offset;
            }
        }

    }

}
