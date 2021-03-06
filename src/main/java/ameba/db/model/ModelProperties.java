package ameba.db.model;

import org.glassfish.jersey.internal.util.PropertiesClass;

/**
 * properties for classes byte code enhance
 *
 * @author icode
 */
@PropertiesClass
public class ModelProperties {
    public static final String GET_FINDER_M_NAME = "withFinder";
    public static final String GET_UPDATE_M_NAME = "withUpdater";
    public static final String GET_PERSISTER_M_NAME = "withPersister";
    public static final String BASE_MODEL_PKG = ModelProperties.class.getPackage().getName();
    public static final String FINDER_C_NAME = BASE_MODEL_PKG + ".Finder";
    public static final String UPDATER_C_NAME = BASE_MODEL_PKG + ".Updater";
    public static final String PERSISTER_C_NAME = BASE_MODEL_PKG + ".Persister";
    public static final String BASE_MODEL_NAME = BASE_MODEL_PKG + ".Model";
}
