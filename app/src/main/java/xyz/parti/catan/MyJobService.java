package xyz.parti.catan;

import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;


public class MyJobService extends JobService
{
	@Override
	public boolean onStartJob(JobParameters jobParameters)
	{
		Util.d("Performing long running task in scheduled job");
		// TODO(developer): add long running task here.
		return false;
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters)
	{
		return false;
	}

}
