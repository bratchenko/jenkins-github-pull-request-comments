package org.jenkinsci.plugins.ghprc.extensions;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.collections.Predicate;
import org.apache.commons.collections.PredicateUtils;
import org.apache.commons.collections.functors.InstanceofPredicate;

import jenkins.model.Jenkins;
import hudson.DescriptorExtensionList;
import hudson.model.Descriptor;

@SuppressWarnings("unchecked")
public abstract class GhprcExtensionDescriptor extends Descriptor<GhprcExtension> {

    public boolean isApplicable(Class<?> type) {
        return true;
    }

    public static DescriptorExtensionList<GhprcExtension, GhprcExtensionDescriptor> getExtensions(Class<? extends GhprcExtensionType>... types) {
        DescriptorExtensionList<GhprcExtension, GhprcExtensionDescriptor> list = Jenkins.getInstance().getDescriptorList(GhprcExtension.class);
        filterExtensions(list, types);
        return list;
    }

    private static void filterExtensions(DescriptorExtensionList<GhprcExtension, GhprcExtensionDescriptor> descriptors, Class<? extends GhprcExtensionType>... types) {
        List<Predicate> predicates = new ArrayList<Predicate>(types.length);
        for (Class<? extends GhprcExtensionType> type : types) {
            predicates.add(InstanceofPredicate.getInstance(type));

        }
        Predicate anyPredicate = PredicateUtils.anyPredicate(predicates);
        for (GhprcExtensionDescriptor descriptor : descriptors) {
            if (!anyPredicate.evaluate(descriptor)) {
                descriptors.remove(descriptor);
            }
        }
    }

    public static DescriptorExtensionList<GhprcExtension, GhprcExtensionDescriptor> allProject() {
        DescriptorExtensionList<GhprcExtension, GhprcExtensionDescriptor> list = Jenkins.getInstance().getDescriptorList(GhprcExtension.class);
        filterExtensions(list, GhprcProjectExtension.class);
        return list;
    }

    public static DescriptorExtensionList<GhprcExtension, GhprcExtensionDescriptor> allGlobal() {
        DescriptorExtensionList<GhprcExtension, GhprcExtensionDescriptor> list = Jenkins.getInstance().getDescriptorList(GhprcExtension.class);
        filterExtensions(list, GhprcGlobalExtension.class);
        return list;
    }

}