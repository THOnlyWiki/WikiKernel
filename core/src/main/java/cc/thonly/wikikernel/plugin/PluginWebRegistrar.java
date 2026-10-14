package cc.thonly.wikikernel.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把插件子容器里的 {@code @Controller}/{@code @RestController} 动态注册到 core 的 MVC。
 * <p>
 * 插件子容器不是 Web 容器，DispatcherServlet 不会自动发现它里面的 Controller，
 * 所以这里把插件的请求映射手工注册进 core 的 RequestMappingHandlerMapping；
 * 插件停止时再注销，避免路由和 Bean 泄漏。
 */
@Component
public class PluginWebRegistrar {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final Map<String, List<RequestMappingInfo>> registered = new ConcurrentHashMap<>();

    public PluginWebRegistrar(ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        this.handlerMappingProvider = handlerMappingProvider;
    }

    /** 注册插件里所有 Controller 的请求映射。 */
    public void register(String pluginId, ApplicationContext pluginContext) {
        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping == null) {
            return;
        }
        List<RequestMappingInfo> infos = new ArrayList<>();
        Map<String, Object> controllers = pluginContext.getBeansWithAnnotation(Controller.class);
        for (Object handler : controllers.values()) {
            Class<?> handlerType = ClassUtils.getUserClass(handler);
            Map<Method, RequestMappingInfo> methods = MethodIntrospector.selectMethods(handlerType,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method ->
                            buildMappingInfo(mapping, handlerType, method));
            methods.forEach((method, info) -> {
                mapping.registerMapping(info, handler, method);
                infos.add(info);
            });
        }
        if (!infos.isEmpty()) {
            registered.put(pluginId, infos);
        }
    }

    /** 注销某个插件的全部请求映射。 */
    public void unregister(String pluginId) {
        List<RequestMappingInfo> infos = registered.remove(pluginId);
        RequestMappingHandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping == null || infos == null) {
            return;
        }
        infos.forEach(mapping::unregisterMapping);
    }

    private RequestMappingInfo buildMappingInfo(RequestMappingHandlerMapping mapping,
                                                Class<?> handlerType,
                                                Method method) {
        RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) {
            return null;
        }
        RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(handlerType, RequestMapping.class);
        return RequestMappingInfo.paths(combinePatterns(typeMapping, methodMapping))
                .methods(methodMapping.method())
                .params(methodMapping.params())
                .headers(methodMapping.headers())
                .consumes(methodMapping.consumes())
                .produces(methodMapping.produces())
                .options(mapping.getBuilderConfiguration())
                .build();
    }

    private String[] combinePatterns(RequestMapping typeMapping, RequestMapping methodMapping) {
        String[] typePaths = typeMapping != null ? typeMapping.path() : new String[0];
        String[] methodPaths = methodMapping.path();
        if (typePaths.length == 0) {
            return methodPaths;
        }
        if (methodPaths.length == 0) {
            return typePaths;
        }
        List<String> patterns = new ArrayList<>();
        for (String typePath : typePaths) {
            for (String methodPath : methodPaths) {
                patterns.add(join(typePath, methodPath));
            }
        }
        return patterns.toArray(String[]::new);
    }

    private String join(String prefix, String path) {
        String left = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String right = path.startsWith("/") ? path : "/" + path;
        return left + right;
    }
}
