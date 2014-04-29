# AUTOMATICALLY GENERATED FROM 'celeryconfig.properties'
# DO NOT CHANGE MANUALLY
#
# execute "mvn resources:resources" to trigger generation,
# created files will be in /target
# Copy this generated celeryconfig.py to worker nodes running Celery,
# along with the python files containing the tasks
#  

#
# MACHINE SETTINGS:
# You may want to customise these for each physical machine you run Celery workers on. 
#

#CELERYD_CONCURRENCY = ${celery.worker.workerthreads}
CELERYD_CONCURRENCY = ${celery.worker.workerthreads}
#CELERYD_LOG_FILE    = ${celery.worker.logfile}
CELERYD_LOG_FILE    = ${celery.worker.logfile}

#
# COMMON SETTINGS:
# these should be identical on all machines listening to the same job queue.
#

# List of tasks
CELERY_IMPORTS = (${celery.tasks})

BROKER_URL = "${broker.url}"

# Result send-backs
CELERY_RESULT_BACKEND = "amqp"
CELERY_RESULT_SERIALIZER = "json"
CELERY_TRACK_STARTED = True

# Variable queue settings
CELERY_DEFAULT_QUEUE = "${celery.submit.queue}"
CELERY_QUEUES = {
    "${celery.submit.queue}": {"exchange":"${celery.submit.exchange}", "binding_key":"${celery.submit.routekey}"}, 
}


CELERY_RESULT_EXCHANGE = "${celery.result.exchange}"
CELERY_TASK_RESULT_EXPIRES = ${celery.result.expiresecs}

CELERY_BROADCAST_EXCHANGE = "${celery.broadcast.exchange}" # Seems to be ignored, celery uses "celery.pidbox"

