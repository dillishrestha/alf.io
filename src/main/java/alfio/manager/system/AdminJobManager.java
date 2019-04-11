/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager.system;

import alfio.manager.system.AdminJobExecutor.JobName;
import alfio.model.result.ErrorCode;
import alfio.model.result.Result;
import alfio.model.system.AdminJobSchedule;
import alfio.repository.system.AdminJobQueueRepository;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.*;

@Component
@Transactional
@Log4j2
public class AdminJobManager {

    private final Map<JobName, List<AdminJobExecutor>> executorsByJobId;
    private final AdminJobQueueRepository adminJobQueueRepository;
    private final TransactionTemplate nestedTransactionTemplate;

    public AdminJobManager(List<AdminJobExecutor> jobExecutors,
                           AdminJobQueueRepository adminJobQueueRepository,
                           PlatformTransactionManager transactionManager) {

        this.executorsByJobId = jobExecutors.stream()
            .flatMap(je -> je.getJobNames().stream().map(n -> Pair.of(n, je)))
            .collect(groupingBy(Pair::getLeft, () -> new EnumMap<>(JobName.class), mapping(Pair::getValue, toList())));
        this.adminJobQueueRepository = adminJobQueueRepository;
        this.nestedTransactionTemplate = new TransactionTemplate(transactionManager, new DefaultTransactionDefinition((TransactionDefinition.PROPAGATION_NESTED)));
    }

    @Scheduled(fixedDelay = 60 * 1000)
    void processPendingRequests() {
        adminJobQueueRepository.loadPendingSchedules()
            .stream()
            .map(this::processPendingRequest)
            .filter(p -> !p.getRight().isEmpty())
            .forEach(scheduleWithResults -> {
                var schedule = scheduleWithResults.getLeft();
                var partitionedResults = scheduleWithResults.getRight().stream().collect(Collectors.partitioningBy(Result::isSuccess));
                if(!partitionedResults.get(false).isEmpty()) {
                    partitionedResults.get(false).forEach(r -> log.warn("Processing failed for {}: {}", schedule.getJobName(), r.getErrors()));
                    adminJobQueueRepository.updateSchedule(schedule.getId(), AdminJobSchedule.Status.FAILED, ZonedDateTime.now(), Map.of());
                } else {
                    partitionedResults.get(true).forEach(result -> {
                        if(result.getData() != null) {
                            log.debug("Message from {}: {}", schedule.getJobName(), result.getData());
                        }
                    });
                    adminJobQueueRepository.updateSchedule(schedule.getId(), AdminJobSchedule.Status.EXECUTED, ZonedDateTime.now(), Map.of());
                }
            });
    }

    public boolean scheduleExecution(JobName jobName, Map<String, Object> metadata) {
        try {
            adminJobQueueRepository.schedule(jobName, ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES), metadata);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.trace("Integrity violation", ex);
            return false;
        }
    }

    private Pair<AdminJobSchedule, List<Result<String>>> processPendingRequest(AdminJobSchedule schedule) {
        return Pair.of(schedule, executorsByJobId.getOrDefault(schedule.getJobName(), List.of())
            .stream()
            .map(s -> {
                try {
                    return Result.success(nestedTransactionTemplate.execute(status -> s.process(schedule)));
                } catch (Exception ex) {
                    return Result.<String>error(ErrorCode.custom("exception", ex.getMessage()));
                }
            })
            .collect(Collectors.toList()));
    }
}
