package com.avast.grpc.jwt.server;

import com.avast.grpc.jwt.Constants;
import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwtServerInterceptor<T> implements ServerInterceptor {
  private static Logger LOGGER = LoggerFactory.getLogger(JwtServerInterceptor.class);

  public final io.grpc.Context.Key<T> AccessTokenContextKey = Context.key("AccessToken");

  private static final String AUTH_HEADER_PREFIX = "Bearer ";

  private final JwtTokenParser<T> tokenParser;

  public JwtServerInterceptor(JwtTokenParser<T> tokenParser) {
    this.tokenParser = tokenParser;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String authHeader = headers.get(Constants.AuthorizationMetadataKey);
    if (authHeader == null) {
      String msg = Constants.AuthorizationMetadataKey.name() + " header not found";
      LOGGER.warn(msg);
      call.close(Status.UNAUTHENTICATED.withDescription(msg), new Metadata());
      return new ServerCall.Listener<ReqT>() {};
    }
    if (!authHeader.startsWith(AUTH_HEADER_PREFIX)) {
      String msg =
          Constants.AuthorizationMetadataKey.name()
              + " header does not start with "
              + AUTH_HEADER_PREFIX;
      LOGGER.warn(msg);
      call.close(Status.UNAUTHENTICATED.withDescription(msg), new Metadata());
      return new ServerCall.Listener<ReqT>() {};
    }
    DelayedServerCallListener<ReqT> delayedListener = new DelayedServerCallListener<>();
    Context context = Context.current(); // we must call this on the right thread
    try {
      tokenParser
          .parseToValid(authHeader.substring(AUTH_HEADER_PREFIX.length()))
          .whenComplete(
              (token, e) ->
                  context.run(
                      () -> {
                        if (e == null) {
                          delayedListener.setDelegate(
                              Contexts.interceptCall(
                                  Context.current().withValue(AccessTokenContextKey, token),
                                  call,
                                  headers,
                                  next));
                        } else {
                          delayedListener.setDelegate(handleException(e, call));
                        }
                      }));
    } catch (Exception e) {
      return handleException(e, call);
    }
    return delayedListener;
  }

  private <ReqT, RespT> ServerCall.Listener<ReqT> handleException(
      Throwable e, ServerCall<ReqT, RespT> call) {
    String msg =
        Constants.AuthorizationMetadataKey.name() + " header validation failed: " + e.getMessage();
    LOGGER.warn(msg, e);
    call.close(Status.UNAUTHENTICATED.withDescription(msg).withCause(e), new Metadata());
    return new ServerCall.Listener<ReqT>() {};
  }
}
