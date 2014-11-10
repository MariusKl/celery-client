package nl.ru.cmbi.celery;

import lombok.Getter;

/** Possible states for a Celery job */
public enum JobState {
	/** Submitted, but not accepted by a worker node yet */
	WAITING(false),
	/** Running on a worker, but not finished yet */
	RUNNING(false),
	/** Successfully ran, result available via {@link CeleryFuture#get()} */
	COMPLETED(true),
	/** Problem before completing, no result, but exception available via {@link CeleryFuture#get()} */
	FAILED(true),
	/** Job was canceled by user */
	CANCELLED(true), ;

	@Getter
	private final boolean	isDoneState;

	/**
	 * @param isDoneState
	 *            indicates whether this state means the job is done
	 */
	private JobState(final boolean isDoneState) {
		this.isDoneState = isDoneState;
	}
	
	public boolean isDoneState(){
		return this.isDoneState;
	}
}
