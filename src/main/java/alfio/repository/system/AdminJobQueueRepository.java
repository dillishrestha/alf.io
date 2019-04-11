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
package alfio.repository.system;

import alfio.manager.system.AdminJobExecutor.JobName;
import alfio.model.support.JSONData;
import alfio.model.system.AdminJobSchedule;
import ch.digitalfondue.npjt.Bind;
import ch.digitalfondue.npjt.Query;
import ch.digitalfondue.npjt.QueryRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@QueryRepository
public interface AdminJobQueueRepository {

    @Query("select * from admin_job_queue where status = 'SCHEDULED' for update skip locked")
    List<AdminJobSchedule> loadPendingSchedules();

    @Query("update admin_job_queue set status = :status, execution_ts = :executionDate, metadata = to_json(:metadata::json) where id = :id")
    int updateSchedule(@Bind("id") long id,
                       @Bind("status") AdminJobSchedule.Status status,
                       @Bind("executionDate")ZonedDateTime executionDate,
                       @Bind("metadata") @JSONData Map<String, Object> metadata);

    @Query("insert into admin_job_queue(job_name, request_ts, metadata, status) values(:jobName, :requestTs, to_json(:metadata::json), 'SCHEDULED')")
    int schedule(@Bind("jobName") JobName jobName,
                 @Bind("requestTs") ZonedDateTime requestTimestamp,
                 @Bind("metadata") @JSONData Map<String, Object> metadata);
}
