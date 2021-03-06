
package com.creactiviti.piper.core;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import com.creactiviti.piper.core.context.JdbcContextRepository;
import com.creactiviti.piper.core.job.JdbcJobRepository;
import com.creactiviti.piper.core.job.Job;
import com.creactiviti.piper.core.job.JobStatus;
import com.creactiviti.piper.core.messenger.Queues;
import com.creactiviti.piper.core.messenger.SynchMessenger;
import com.creactiviti.piper.core.pipeline.ResourceBasedPipelineRepository;
import com.creactiviti.piper.core.task.DefaultTaskHandlerResolver;
import com.creactiviti.piper.core.task.JdbcTaskExecutionRepository;
import com.creactiviti.piper.core.task.TaskExecution;
import com.creactiviti.piper.core.task.TaskHandler;
import com.creactiviti.piper.core.task.WorkTaskDispatcher;
import com.creactiviti.piper.core.taskhandler.io.Print;
import com.creactiviti.piper.core.taskhandler.random.RandomInt;
import com.creactiviti.piper.core.taskhandler.time.Sleep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableMap;

@RunWith(SpringRunner.class)
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2)
public class CoordinatorTests {

  @Autowired
  private DataSource dataSource;
  
  @Test
  public void testStartJob () throws SQLException {
    Worker worker = new Worker();
    Coordinator coordinator = new Coordinator ();
   
    SynchMessenger messenger = new SynchMessenger();
    messenger.receive(Queues.COMPLETIONS, (o)->coordinator.complete((TaskExecution)o));
    messenger.receive(Queues.JOBS, (o)->coordinator.start((Job)o));
    
    
    worker.setMessenger(messenger);
    worker.setEventPublisher((e)->{});
    DefaultTaskHandlerResolver taskHandlerResolver = new DefaultTaskHandlerResolver();
    
    Map<String,TaskHandler<?>> handlers = new HashMap<>();
    handlers.put("print", new Print());
    handlers.put("randomInt", new RandomInt());
    handlers.put("sleep", new Sleep());
    
    taskHandlerResolver.setTaskHandlers(handlers);
    
    worker.setTaskHandlerResolver(taskHandlerResolver);
    
    ObjectMapper objectMapper = createObjectMapper();
    
    JdbcContextRepository contextRepository = new JdbcContextRepository();
    contextRepository.setJdbcTemplate(new JdbcTemplate(dataSource));
    contextRepository.setObjectMapper(objectMapper);
    
    coordinator.setContextRepository(contextRepository);
        
    JdbcTaskExecutionRepository taskRepository = new JdbcTaskExecutionRepository();
    taskRepository.setJdbcOperations(new NamedParameterJdbcTemplate(dataSource));
    taskRepository.setObjectMapper(createObjectMapper());
    
    JdbcJobRepository jobRepository = new JdbcJobRepository();
    jobRepository.setJdbcOperations(new NamedParameterJdbcTemplate(dataSource));
    jobRepository.setJobTaskRepository(taskRepository);
    
    coordinator.setJobRepository(jobRepository);
    coordinator.setPipelineRepository(new ResourceBasedPipelineRepository());
    coordinator.setJobTaskRepository(taskRepository);
    
    SynchMessenger coordinatorMessenger = new SynchMessenger();
    coordinatorMessenger.receive(Queues.TASKS, (o)->worker.handle((TaskExecution)o));
    WorkTaskDispatcher taskDispatcher = new WorkTaskDispatcher(coordinatorMessenger);
    coordinator.setTaskDispatcher(taskDispatcher);
    coordinator.setEventPublisher((e)->{});
    
    DefaultJobExecutor jobExecutor = new DefaultJobExecutor ();
    jobExecutor.setContextRepository(contextRepository);
    jobExecutor.setJobTaskRepository(taskRepository);
    jobExecutor.setPipelineRepository(new ResourceBasedPipelineRepository());
    jobExecutor.setTaskDispatcher(taskDispatcher);
    coordinator.setJobExecutor(jobExecutor);
    
    DefaultTaskCompletionHandler taskCompletionHandler = new DefaultTaskCompletionHandler();
    taskCompletionHandler.setContextRepository(contextRepository);
    taskCompletionHandler.setJobExecutor(jobExecutor);
    taskCompletionHandler.setJobRepository(jobRepository);
    taskCompletionHandler.setJobTaskRepository(taskRepository);
    taskCompletionHandler.setPipelineRepository(new ResourceBasedPipelineRepository());
    taskCompletionHandler.setEventPublisher((e)->{});
    coordinator.setTaskCompletionHandler(taskCompletionHandler);
    coordinator.setMessenger(messenger);
        
    Job job = coordinator.create(MapObject.of(ImmutableMap.of("pipelineId","demo/hello","inputs",Collections.singletonMap("yourName","me"))));
    
    Job completedJob = jobRepository.findOne(job.getId());
    
    Assert.assertEquals(JobStatus.COMPLETED, completedJob.getStatus());
  }

  private ObjectMapper createObjectMapper() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ"));
    return objectMapper;
  }
  
  @Test(expected=IllegalArgumentException.class)
  public void testRequiredParams () {
    Coordinator coordinator = new Coordinator ();
    coordinator.setPipelineRepository(new ResourceBasedPipelineRepository());
    coordinator.create(MapObject.of(Collections.singletonMap("pipelineId","demo/hello")));
  }
  
}