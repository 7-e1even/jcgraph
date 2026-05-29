package io.jcgraph.security;

import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

/**
 * Heuristic classification of "attack-surface entry points": methods reachable
 * from outside the JVM (HTTP, MQ, JNI, scheduler, main). Distinguishing real
 * entries from arbitrary library leaves is the single biggest signal-to-noise
 * lever for an audit agent — a taint flow rooted at a real HTTP handler is
 * orders of magnitude more interesting than one rooted at a private helper.
 *
 * <p>The rules cover the common Java ecosystems (servlet, Spring web, Spring
 * messaging, java.lang.Runnable / Callable). Both {@code javax.*} and
 * {@code jakarta.*} packages are recognized so older and Jakarta EE 9+ code
 * both classify correctly.</p>
 */
public final class EntryRules {

    public static final String MAIN = "MAIN";
    public static final String SERVLET = "SERVLET";
    public static final String FILTER = "FILTER";
    public static final String HTTP = "HTTP";
    public static final String MQ = "MQ";
    public static final String ASYNC = "ASYNC";

    private static final Set<String> HTTP_METHOD_ANNOTATIONS = setOf(
            "Lorg/springframework/web/bind/annotation/RequestMapping;",
            "Lorg/springframework/web/bind/annotation/GetMapping;",
            "Lorg/springframework/web/bind/annotation/PostMapping;",
            "Lorg/springframework/web/bind/annotation/PutMapping;",
            "Lorg/springframework/web/bind/annotation/DeleteMapping;",
            "Lorg/springframework/web/bind/annotation/PatchMapping;",
            "Ljavax/ws/rs/Path;",
            "Ljakarta/ws/rs/Path;",
            "Ljavax/ws/rs/GET;",
            "Ljavax/ws/rs/POST;",
            "Ljavax/ws/rs/PUT;",
            "Ljavax/ws/rs/DELETE;",
            "Ljakarta/ws/rs/GET;",
            "Ljakarta/ws/rs/POST;",
            "Ljakarta/ws/rs/PUT;",
            "Ljakarta/ws/rs/DELETE;"
    );

    private static final Set<String> HTTP_CLASS_ANNOTATIONS = setOf(
            "Lorg/springframework/web/bind/annotation/RestController;",
            "Lorg/springframework/stereotype/Controller;",
            "Lorg/springframework/web/bind/annotation/RequestMapping;",
            "Ljavax/ws/rs/Path;",
            "Ljakarta/ws/rs/Path;"
    );

    private static final Set<String> MQ_ANNOTATIONS = setOf(
            "Lorg/springframework/kafka/annotation/KafkaListener;",
            "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;",
            "Lorg/springframework/jms/annotation/JmsListener;",
            "Lorg/springframework/cloud/stream/annotation/StreamListener;",
            "Lorg/springframework/messaging/handler/annotation/MessageMapping;"
    );

    private static final Set<String> ASYNC_ANNOTATIONS = setOf(
            "Lorg/springframework/scheduling/annotation/Scheduled;",
            "Lorg/springframework/scheduling/annotation/Async;"
    );

    private EntryRules() {
    }

    /** {@code public static void main(String[])} entry method? */
    public static String fromMain(int access, String name, String descriptor) {
        if ((access & Opcodes.ACC_STATIC) != 0
                && "main".equals(name)
                && "([Ljava/lang/String;)V".equals(descriptor)) {
            return MAIN;
        }
        return null;
    }

    /** Classify a method by a single annotation descriptor it carries. */
    public static String fromMethodAnnotation(String annDesc) {
        if (HTTP_METHOD_ANNOTATIONS.contains(annDesc)) return HTTP;
        if (MQ_ANNOTATIONS.contains(annDesc)) return MQ;
        if (ASYNC_ANNOTATIONS.contains(annDesc)) return ASYNC;
        return null;
    }

    /** Classify a method indirectly via an annotation on its declaring class. */
    public static String fromClassAnnotation(String annDesc) {
        if (HTTP_CLASS_ANNOTATIONS.contains(annDesc)) return HTTP;
        return null;
    }

    /**
     * Classify by override target: e.g. a method that overrides
     * {@code HttpServlet#doGet} is a SERVLET handler regardless of annotations.
     * Returns {@code null} if the parent is not a known entry-shaped method.
     */
    public static String fromOverride(String parentOwner, String name, String descriptor) {
        if (isServletBase(parentOwner)) {
            if (name.startsWith("do") || "service".equals(name)) {
                return SERVLET;
            }
        }
        if (isFilterBase(parentOwner) && "doFilter".equals(name)) {
            return FILTER;
        }
        if (isJmsListener(parentOwner) && "onMessage".equals(name)) {
            return MQ;
        }
        if ("java/lang/Runnable".equals(parentOwner)
                && "run".equals(name) && "()V".equals(descriptor)) {
            return ASYNC;
        }
        if ("java/util/concurrent/Callable".equals(parentOwner) && "call".equals(name)) {
            return ASYNC;
        }
        return null;
    }

    private static boolean isServletBase(String owner) {
        return "javax/servlet/http/HttpServlet".equals(owner)
                || "jakarta/servlet/http/HttpServlet".equals(owner);
    }

    private static boolean isFilterBase(String owner) {
        return "javax/servlet/Filter".equals(owner)
                || "jakarta/servlet/Filter".equals(owner);
    }

    private static boolean isJmsListener(String owner) {
        return "javax/jms/MessageListener".equals(owner)
                || "jakarta/jms/MessageListener".equals(owner);
    }

    /** Priority for ranking: lower index = more interesting (real attack surface). */
    public static int priority(String entryKind) {
        if (entryKind == null) return 100;
        switch (entryKind) {
            case HTTP: return 0;
            case SERVLET: return 1;
            case FILTER: return 2;
            case MQ: return 3;
            case MAIN: return 4;
            case ASYNC: return 5;
            default: return 50;
        }
    }

    private static Set<String> setOf(String... items) {
        Set<String> s = new HashSet<>();
        for (String item : items) {
            s.add(item);
        }
        return s;
    }
}
