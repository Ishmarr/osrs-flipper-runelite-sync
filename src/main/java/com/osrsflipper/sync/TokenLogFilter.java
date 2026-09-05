package com.osrsflipper.sync;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/** Suppress legacy ConfigManager token logs, including profile loads before plugin startup. */
public final class TokenLogFilter extends TurboFilter
{
    public static synchronized void install()
    {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext)) return;
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (TurboFilter filter : context.getTurboFilterList())
            if (filter instanceof TokenLogFilter) return;
        TokenLogFilter filter = new TokenLogFilter();
        filter.start();
        context.addTurboFilter(filter);
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
        Object[] parameters, Throwable throwable)
    {
        if (containsSecret(format)) return FilterReply.DENY;
        if (parameters != null)
            for (Object parameter : parameters)
                if (parameter instanceof String && containsSecret((String) parameter)) return FilterReply.DENY;
        return FilterReply.NEUTRAL;
    }

    private static boolean containsSecret(String value)
    {
        return value != null && (value.contains("osrsflippersync.deviceToken") ||
            (value.contains("rlt_") && value.matches("(?s).*rlt_[A-Za-z0-9_-]{40,120}.*")));
    }
}
