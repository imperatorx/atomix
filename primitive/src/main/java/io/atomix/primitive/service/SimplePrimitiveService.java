package io.atomix.primitive.service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.protobuf.ByteString;
import io.atomix.primitive.operation.CommandId;
import io.atomix.primitive.operation.QueryId;
import io.atomix.primitive.service.impl.CommandRequest;
import io.atomix.primitive.service.impl.CommandResponse;
import io.atomix.primitive.service.impl.DefaultServiceExecutor;
import io.atomix.primitive.service.impl.QueryRequest;
import io.atomix.primitive.service.impl.QueryResponse;
import io.atomix.primitive.service.impl.ResponseContext;
import io.atomix.primitive.service.impl.StreamContext;
import io.atomix.primitive.service.impl.StreamResponse;
import io.atomix.primitive.util.ByteArrayDecoder;
import io.atomix.utils.stream.StreamHandler;

/**
 * Simple primitive service.
 */
public abstract class SimplePrimitiveService extends AbstractPrimitiveService implements PrimitiveService {
  private DefaultServiceExecutor executor;
  private final Map<Long, List<Runnable>> indexQueries = new HashMap<>();

  @Override
  public void init(StateMachine.Context context) {
    this.executor = new DefaultServiceExecutor(context);
    super.init(executor, executor, executor);
    configure(executor);
  }

  @Override
  public boolean canDelete(long index) {
    return true;
  }

  @Override
  public CompletableFuture<byte[]> apply(Command<byte[]> command) {
    return applyCommand(command.map(bytes -> ByteArrayDecoder.decode(bytes, CommandRequest::parseFrom)))
        .thenApply(CommandResponse::toByteArray);
  }

  private CompletableFuture<CommandResponse> applyCommand(Command<CommandRequest> command) {
    CommandId commandId = new CommandId(command.value().getName());
    byte[] output = executor.apply(commandId, command.map(r -> r.getCommand().toByteArray()));
    return CompletableFuture.completedFuture(CommandResponse.newBuilder()
        .setContext(ResponseContext.newBuilder()
            .setIndex(getCurrentIndex())
            .build())
        .setOutput(ByteString.copyFrom(output))
        .build());
  }

  @Override
  public CompletableFuture<Void> apply(Command<byte[]> command, StreamHandler<byte[]> handler) {
    return applyCommand(command.map(bytes -> ByteArrayDecoder.decode(bytes, CommandRequest::parseFrom)), new StreamHandler<StreamResponse>() {
      @Override
      public void next(StreamResponse response) {
        handler.next(response.toByteArray());
      }

      @Override
      public void complete() {
        handler.complete();
      }

      @Override
      public void error(Throwable error) {
        handler.error(error);
      }
    });
  }

  private CompletableFuture<Void> applyCommand(Command<CommandRequest> command, StreamHandler<StreamResponse> handler) {
    CommandId commandId = new CommandId(command.value().getName());
    executor.apply(commandId, command.map(r -> r.getCommand().toByteArray()), new StreamHandler<byte[]>() {
      @Override
      public void next(byte[] value) {
        handler.next(StreamResponse.newBuilder()
            .setContext(StreamContext.newBuilder()
                .setIndex(getCurrentIndex())
                .build())
            .setOutput(ByteString.copyFrom(value))
            .build());
      }

      @Override
      public void complete() {
        handler.complete();
      }

      @Override
      public void error(Throwable error) {
        handler.error(error);
      }
    });
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<byte[]> apply(Query<byte[]> query) {
    return applyQuery(query.map(bytes -> ByteArrayDecoder.decode(bytes, QueryRequest::parseFrom)))
        .thenApply(QueryResponse::toByteArray);
  }

  private CompletableFuture<QueryResponse> applyQuery(Query<QueryRequest> query) {
    CompletableFuture<QueryResponse> future = new CompletableFuture<>();
    if (query.value().getContext().getIndex() > getCurrentIndex()) {
      indexQueries.computeIfAbsent(query.value().getContext().getIndex(), index -> new LinkedList<>())
          .add(() -> applyQuery(query, future));
    } else {
      applyQuery(query, future);
    }
    return future;
  }

  private void applyQuery(Query<QueryRequest> request, CompletableFuture<QueryResponse> future) {
    QueryId queryId = new QueryId(request.value().getName());
    byte[] output = executor.apply(queryId, request.map(r -> r.getQuery().toByteArray()));
    future.complete(QueryResponse.newBuilder()
        .setContext(ResponseContext.newBuilder()
            .setIndex(getCurrentIndex())
            .build())
        .setOutput(ByteString.copyFrom(output))
        .build());
  }

  @Override
  public CompletableFuture<Void> apply(Query<byte[]> query, StreamHandler<byte[]> handler) {
    return applyQuery(query.map(bytes -> ByteArrayDecoder.decode(bytes, QueryRequest::parseFrom)), new StreamHandler<StreamResponse>() {
      @Override
      public void next(StreamResponse response) {
        handler.next(response.toByteArray());
      }

      @Override
      public void complete() {
        handler.complete();
      }

      @Override
      public void error(Throwable error) {
        handler.error(error);
      }
    });
  }

  private CompletableFuture<Void> applyQuery(Query<QueryRequest> query, StreamHandler<StreamResponse> handler) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    if (query.value().getContext().getIndex() > getCurrentIndex()) {
      indexQueries.computeIfAbsent(query.value().getContext().getIndex(), index -> new LinkedList<>())
          .add(() -> applyQuery(query, handler, future));
    } else {
      applyQuery(query, handler, future);
    }
    return future;
  }

  private void applyQuery(Query<QueryRequest> query, StreamHandler<StreamResponse> handler, CompletableFuture<Void> future) {
    QueryId queryId = new QueryId(query.value().getName());
    executor.apply(queryId, query.map(r -> r.getQuery().toByteArray()), new StreamHandler<byte[]>() {
      @Override
      public void next(byte[] value) {
        handler.next(StreamResponse.newBuilder()
            .setContext(StreamContext.newBuilder()
                .setIndex(getCurrentIndex())
                .build())
            .setOutput(ByteString.copyFrom(value))
            .build());
      }

      @Override
      public void complete() {
        handler.complete();
      }

      @Override
      public void error(Throwable error) {
        handler.error(error);
      }
    });
    future.complete(null);
  }
}
