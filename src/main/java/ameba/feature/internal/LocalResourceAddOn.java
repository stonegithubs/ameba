package ameba.feature.internal;

import ameba.core.AddOn;
import ameba.core.Application;
import ameba.event.Listener;
import ameba.event.SystemEventBus;
import com.google.common.collect.Sets;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import java.util.Set;

/**
 * @author icode
 */
public class LocalResourceAddOn extends AddOn {
    @Override
    public void setup(Application application) {

        final Set<Application.ClassFoundEvent.ClassInfo> classInfoSet = Sets.newLinkedHashSet();
        SystemEventBus.subscribe(Application.ClassFoundEvent.class, new Listener<Application.ClassFoundEvent>() {
            @Override
            public void onReceive(Application.ClassFoundEvent event) {
                event.accept(new Application.ClassFoundEvent.ClassAccept() {
                    @Override
                    public boolean accept(Application.ClassFoundEvent.ClassInfo info) {
                        if (info.containsAnnotations(Service.class)) {
                            classInfoSet.add(info);
                            return true;
                        }
                        return false;
                    }
                });
            }
        });

        application.register(new Feature() {

            @Inject
            private ServiceLocator locator;

            @Override
            public boolean configure(FeatureContext context) {
                for (Application.ClassFoundEvent.ClassInfo classInfo : classInfoSet) {
                    ServiceLocatorUtilities.addClasses(locator, classInfo.toClass());
                }
                classInfoSet.clear();
                return true;
            }
        });
    }
}
