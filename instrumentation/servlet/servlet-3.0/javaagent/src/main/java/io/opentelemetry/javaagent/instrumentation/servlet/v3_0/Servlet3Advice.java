/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet.v3_0;

import static io.opentelemetry.javaagent.instrumentation.servlet.v3_0.Servlet3HttpServerTracer.tracer;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.servlet.AppServerBridge;
import io.opentelemetry.javaagent.instrumentation.api.CallDepthThreadLocalMap;
import io.opentelemetry.javaagent.instrumentation.api.Java8BytecodeBridge;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

public class Servlet3Advice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.Argument(value = 0, readOnly = false) ServletRequest request,
      @Advice.Argument(value = 1, readOnly = false) ServletResponse response,
      @Advice.Local("otelContext") Context context,
      @Advice.Local("otelScope") Scope scope) {
    CallDepthThreadLocalMap.incrementCallDepth(AppServerBridge.getCallDepthKey());
    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      return;
    }

    HttpServletRequest httpServletRequest = (HttpServletRequest) request;

    Context attachedContext = tracer().getServerContext(httpServletRequest);
    if (attachedContext != null) {
      if (Servlet3HttpServerTracer.needsRescoping(attachedContext)) {
        scope = attachedContext.makeCurrent();
      }

      tracer().updateServerSpanNameOnce(attachedContext, httpServletRequest);
      // We are inside nested servlet/filter/app-server span, don't create new span
      return;
    }

    Context parentContext = Java8BytecodeBridge.currentContext();
    if (parentContext != null && Java8BytecodeBridge.spanFromContext(parentContext).isRecording()) {
      tracer().updateServerSpanNameOnce(parentContext, httpServletRequest);
      // We are inside nested servlet/filter/app-server span, don't create new span
      return;
    }

    context = tracer().startSpan(httpServletRequest);
    scope = context.makeCurrent();
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) ServletRequest request,
      @Advice.Argument(1) ServletResponse response,
      @Advice.Thrown Throwable throwable,
      @Advice.Local("otelContext") Context context,
      @Advice.Local("otelScope") Scope scope) {
    int callDepth = CallDepthThreadLocalMap.decrementCallDepth(AppServerBridge.getCallDepthKey());

    if (scope != null) {
      scope.close();
    }

    if (context == null && callDepth == 0) {
      Context currentContext = Java8BytecodeBridge.currentContext();
      // Something else is managing the context, we're in the outermost level of Servlet
      // instrumentation and we have an uncaught throwable. Let's add it to the current span.
      if (throwable != null) {
        tracer().addUnwrappedThrowable(currentContext, throwable);
      }
      tracer().setPrincipal(currentContext, (HttpServletRequest) request);
    }

    if (scope == null || context == null) {
      return;
    }

    tracer().setPrincipal(context, (HttpServletRequest) request);
    if (throwable != null) {
      tracer().endExceptionally(context, throwable, (HttpServletResponse) response);
      return;
    }

    AtomicBoolean responseHandled = new AtomicBoolean(false);

    // In case of async servlets wait for the actual response to be ready
    if (request.isAsyncStarted()) {
      try {
        request
            .getAsyncContext()
            .addListener(new TagSettingAsyncListener(responseHandled, context));
      } catch (IllegalStateException e) {
        // org.eclipse.jetty.server.Request may throw an exception here if request became
        // finished after check above. We just ignore that exception and move on.
      }
    }

    // Check again in case the request finished before adding the listener.
    if (!request.isAsyncStarted() && responseHandled.compareAndSet(false, true)) {
      tracer().end(context, (HttpServletResponse) response);
    }
  }
}
