package org.ekstep.ep.samza.service;

import static java.text.MessageFormat.format;

import org.ekstep.ep.samza.core.JobMetrics;
import org.ekstep.ep.samza.core.Logger;
import org.ekstep.ep.samza.domain.Event;
import org.ekstep.ep.samza.domain.Location;
import com.google.gson.JsonSyntaxException;
import org.ekstep.ep.samza.task.TelemetryLocationUpdaterConfig;
import org.ekstep.ep.samza.task.TelemetryLocationUpdaterSink;
import org.ekstep.ep.samza.task.TelemetryLocationUpdaterSource;
import org.ekstep.ep.samza.util.DeviceLocationCache;
import redis.clients.jedis.exceptions.JedisException;

public class TelemetryLocationUpdaterService {
	
	private static Logger LOGGER = new Logger(TelemetryLocationUpdaterService.class);
	private DeviceLocationCache deviceLocationCache;
	private JobMetrics metrics;


	public TelemetryLocationUpdaterService(DeviceLocationCache deviceLocationCache, JobMetrics metrics) {
		this.deviceLocationCache = deviceLocationCache;
		this.metrics = metrics;
	}

	public void process(TelemetryLocationUpdaterSource source, TelemetryLocationUpdaterSink sink) {
		Event event = null;
		try {
			event = source.getEvent();
			sink.setMetricsOffset(source.getSystemStreamPartition(), source.getOffset());
			// Add device location details to the event
			event = updateEventWithIPLocation(event);
			System.out.println("Event after location resolution = " + event);
			sink.toSuccessTopic(event);
		} catch (JsonSyntaxException e) {
			LOGGER.error(null, "INVALID EVENT: " + source.getMessage());
			sink.toMalformedTopic(source.getMessage());
		} catch (Exception e) {
			LOGGER.error(null,
					format("EXCEPTION. PASSING EVENT THROUGH AND ADDING IT TO EXCEPTION TOPIC. EVENT: {0}, EXCEPTION:",
							event),
					e);
			sink.toErrorTopic(event, e.getMessage());
		}
	}

	private Event updateEventWithIPLocation(Event event) {
		String did = event.did();
		Location location;
		if (did != null && !did.isEmpty()) {

			location = deviceLocationCache.getLocationForDeviceId(event.did());
			if (location != null) {
				event = updateEvent(event, location, true);
			} else {
				// add empty location
				location = new Location(); // What is the need for empty location? Is it a requirement for druid validator spec?
				event = updateEvent(event, location, false);
			}
			metrics.incProcessedMessageCount();
		} else {
			// add empty location
			location = new Location();
			event = updateEvent(event, location, false);
			metrics.incUnprocessedMessageCount(); // Isn't this skippedCount?
		}
		return event;
	}

	/*
	private Event updateEventWithUserLocation(Event event) {
		try {
			String actorId = event.actorid();
			String actorType = event.actortype();

			if (actorId != null && actorType.equalsIgnoreCase("USER")) {
				Location location = locationEngine.getLocationByUser(actorId);
				if (location == null) {
					event.addUserLocation(new Location(null, null, null, "", null, ""));
				} else {
					event.addUserLocation(location);
				}
			}
			return event;
		} catch(Exception ex) {
			LOGGER.error(null,
					format("EXCEPTION. RESOLVING USER LOCATION. EVENT: {0}, EXCEPTION:",
							event),
					ex);
			event.addUserLocation(new Location(null, null, null, "", null, ""));
			return event;
		}
	}
	*/

	public Event updateEvent(Event event, Location location, Boolean ldataFlag) {
		event.addLocation(location);
		event.removeEdataLoc();
		event.setFlag(TelemetryLocationUpdaterConfig.getDeviceLocationJobFlag(), ldataFlag);
		return event;
	}
}
