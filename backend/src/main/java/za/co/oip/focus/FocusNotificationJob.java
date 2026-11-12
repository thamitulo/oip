package za.co.oip.focus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.oip.notifications.NotificationService;
import za.co.oip.notifications.NotificationService.Type;
import za.co.oip.opportunities.OpportunityQueryService;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Notifies users about newly discovered opportunities in their focus areas (once per opportunity per focus). */
@Component
public class FocusNotificationJob {

    private static final Logger log = LoggerFactory.getLogger(FocusNotificationJob.class);

    private final FocusService focus;
    private final NotificationService notifications;
    private final OpportunityQueryService opportunities;
    private final Clock clock;
    private final boolean scheduled;

    public FocusNotificationJob(FocusService focus, NotificationService notifications, OpportunityQueryService opportunities,
                                Clock clock, @Value("${oip.notifications.jobs-enabled:true}") boolean scheduled) {
        this.scheduled = scheduled;
        this.focus = focus;
        this.notifications = notifications;
        this.opportunities = opportunities;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "${oip.notifications.interval:PT15M}")
    void onSchedule() {
        if (scheduled) {
            run();
        }
    }

    /** Creates notifications for new focus matches; returns how many were created. */
    public int run() {
        int created = 0;
        for (FocusService.Owned o : focus.notifiable()) {
            List<UUID> ids = focus.newMatches(o.focus(), clock.instant().minus(Duration.ofDays(2)), 20);
            for (var s : opportunities.summaries(ids)) {
                if (notifications.notify(o.tenantId(), o.userId(), Type.FOCUS_MATCH, o.focus().name() + ": " + s.title(),
                        (s.organisationName() == null ? "" : s.organisationName() + ". ")
                                + (s.closingDate() == null ? "" : "Closes " + s.closingDate() + "."),
                        "/opportunities/" + s.id(), s.id(), "focus:" + o.focus().id() + ":" + s.id())) {
                    created++;
                }
            }
        }
        if (created > 0) log.info("Created {} focus notification(s)", created);
        return created;
    }
}
