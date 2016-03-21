package io.switchbit.hibersap;

import com.sap.conn.jco.ext.DestinationDataEventListener;
import com.sap.conn.jco.ext.DestinationDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Created by Derick on 3/20/2016.
 */
@Service
public class PropertyDestinationDataProvider implements DestinationDataProvider {

    private final static Logger log = LoggerFactory.getLogger(PropertyDestinationDataProvider.class);
    private final Map<String, Properties> propertiesForDestinationName = new HashMap<String, Properties>();
    private DestinationDataEventListener eventListener;

    public void addDestination(final String destinationName, final Properties properties) {
        log.debug("Add destination " + destinationName + " to " + propertiesForDestinationName);

        propertiesForDestinationName.put(destinationName, properties);
        fireDestinationUpdatedEvent(destinationName);
    }

    public void removeDestination(final String destinationName) {
        log.debug("Remove destination " + destinationName + " from " + propertiesForDestinationName);

        propertiesForDestinationName.remove(destinationName);
        fireDestinationDeletedEvent(destinationName);
    }

    /**
     * {@inheritDoc}
     */
    public Properties getDestinationProperties(final String destinationName) {
        if (wasAdded(destinationName)) {
            return propertiesForDestinationName.get(destinationName);
        } else {
            throw new RuntimeException("No JCo destination with name " + destinationName + " found");
        }
    }

    public boolean wasAdded(final String destinationName) {
        return propertiesForDestinationName.containsKey(destinationName);
    }

    public boolean hasDestinations() {
        return !propertiesForDestinationName.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public void setDestinationDataEventListener(final DestinationDataEventListener eventListener) {
        this.eventListener = eventListener;
    }

    /**
     * {@inheritDoc}
     */
    public boolean supportsEvents() {
        return true;
    }

    private void fireDestinationUpdatedEvent(final String destinationName) {
        if (eventListener != null) {
            eventListener.updated(destinationName);
        }
    }

    private void fireDestinationDeletedEvent(final String destinationName) {
        if (eventListener != null) {
            eventListener.deleted(destinationName);
        }
    }
}
