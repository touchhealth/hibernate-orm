/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.test.actionqueue;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.hibernate.HibernateException;
import org.hibernate.Transaction;
import org.hibernate.TransactionException;
import org.hibernate.action.spi.AfterTransactionCompletionProcess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.engine.transaction.spi.TransactionObserver;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.junit4.BaseCoreFunctionalTestCase;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class CustomAfterCompletionTest extends BaseCoreFunctionalTestCase {

	@Test
	@TestForIssue(jiraKey = "HHH-13666")
	public void success() {
		inSession( session -> {
			AtomicBoolean called = new AtomicBoolean( false );
			session.getActionQueue().registerProcess( new AfterTransactionCompletionProcess() {
				@Override
				public void doAfterTransactionCompletion(boolean success, SharedSessionContractImplementor session) {
					called.set( true );
				}
			} );
			Assert.assertFalse( called.get() );
			inTransaction( session, theSession -> {
				theSession.persist( new SimpleEntity( "jack" ) );
			} );
			Assert.assertTrue( called.get() );
		} );

		// Check that the transaction was committed
		inTransaction( session -> {
			long count = session.createQuery( "select count(*) from SimpleEntity", Long.class )
					.uniqueResult();
			assertEquals( 1L, count );
		} );
	}

	@Test
	@TestForIssue(jiraKey = "HHH-13666")
	public void failure() {
		try {
			inSession( session -> {
				session.getActionQueue().registerProcess( new AfterTransactionCompletionProcess() {
					@Override
					public void doAfterTransactionCompletion(boolean success, SharedSessionContractImplementor session) {
						throw new RuntimeException( "My exception" );
					}
				} );
				inTransaction( session, theSession -> {
					theSession.persist( new SimpleEntity( "jack" ) );
				} );
			} );
			Assert.fail( "Expected exception to be thrown" );
		}
		catch (Exception e) {
			assertThat( e, instanceOf( HibernateException.class ) );
			assertThat( e.getMessage(), containsString( "Unable to perform afterTransactionCompletion callback" ) );
			Throwable cause = e.getCause();
			assertThat( cause, instanceOf( RuntimeException.class ) );
			assertThat( cause.getMessage(), containsString( "My exception" ) );

			// Make sure that the original message is appended to the wrapping exception's message, for convenience
			assertThat( e.getMessage(), containsString( "My exception" ) );
		}

		// Check that the transaction was committed
		inTransaction( session -> {
			long count = session.createQuery( "select count(*) from SimpleEntity", Long.class )
					.uniqueResult();
			assertEquals( 1L, count );
		} );
	}

	@Test
	public void callbackRunsOnJdbcCommitFailure() {
		inSession( session -> {
			AfterTransactionCompletionProcess process = mock( AfterTransactionCompletionProcess.class );
			session.getActionQueue().registerProcess( process );

			Transaction transaction = session.beginTransaction();
			Synchronization synchronization = mock( Synchronization.class );
			transaction.registerSynchronization( synchronization );
			TransactionObserver observer = mock( TransactionObserver.class );
			session.getTransactionCoordinator().addObserver( observer );
			session.doWork( Connection::close );

			TransactionException exception = assertThrows( TransactionException.class, transaction::commit );
			assertThat( exception.getMessage(), containsString( "Unable to commit against JDBC Connection" ) );

			verify( process ).doAfterTransactionCompletion( eq( false ), any() );
			verify( synchronization ).beforeCompletion();
			verify( synchronization ).afterCompletion( Status.STATUS_UNKNOWN );
			verify( observer ).beforeCompletion();
			verify( observer ).afterCompletion( false, false );
			verifyNoMoreInteractions( process, synchronization, observer );
		} );
	}

	@Test
	public void callbackRunsOnJdbcRollbackFailure() {
		inSession( session -> {
			AfterTransactionCompletionProcess process = mock( AfterTransactionCompletionProcess.class );
			session.getActionQueue().registerProcess( process );

			Transaction transaction = session.beginTransaction();
			Synchronization synchronization = mock( Synchronization.class );
			transaction.registerSynchronization( synchronization );
			TransactionObserver observer = mock( TransactionObserver.class );
			session.getTransactionCoordinator().addObserver( observer );
			session.doWork( Connection::close );

			TransactionException exception = assertThrows( TransactionException.class, transaction::rollback );
			assertThat( exception.getMessage(), containsString( "Unable to rollback against JDBC Connection" ) );

			verify( process ).doAfterTransactionCompletion( eq( false ), any() );
			verify( synchronization ).afterCompletion( Status.STATUS_UNKNOWN );
			verify( observer ).afterCompletion( false, false );
			verifyNoMoreInteractions( process, synchronization, observer );
		} );
	}

	@Test
	public void callbackRunsOnBeforeCompletionAndJdbcRollbackFailure() {
		inSession( session -> {
			AfterTransactionCompletionProcess process = mock( AfterTransactionCompletionProcess.class );
			session.getActionQueue().registerProcess( process );

			Transaction transaction = session.beginTransaction();
			Synchronization synchronization = mock( Synchronization.class );
			doThrow( new IllegalStateException( "before completion failure" ) ).when( synchronization ).beforeCompletion();
			transaction.registerSynchronization( synchronization );
			TransactionObserver observer = mock( TransactionObserver.class );
			session.getTransactionCoordinator().addObserver( observer );
			session.doWork( Connection::close );

			RuntimeException exception = assertThrows( RuntimeException.class, transaction::commit );
			assertThat( exception.getMessage(), containsString( "beforeCompletion" ) );

			verify( process ).doAfterTransactionCompletion( eq( false ), any() );
			verify( synchronization ).beforeCompletion();
			verify( synchronization ).afterCompletion( Status.STATUS_UNKNOWN );
			verify( observer ).afterCompletion( false, false );
			verifyNoMoreInteractions( process, synchronization, observer );
		} );
	}

	@Test
	public void callbackRunsOnceOnBeforeCompletionFailureAndLaterRollback() {
		inSession( session -> {
			AfterTransactionCompletionProcess process = mock( AfterTransactionCompletionProcess.class );
			session.getActionQueue().registerProcess( process );

			Transaction transaction = session.beginTransaction();
			Synchronization synchronization = mock( Synchronization.class );
			doThrow( new IllegalStateException( "before completion failure" ) ).when( synchronization ).beforeCompletion();
			transaction.registerSynchronization( synchronization );
			TransactionObserver observer = mock( TransactionObserver.class );
			session.getTransactionCoordinator().addObserver( observer );

			RuntimeException exception = assertThrows( RuntimeException.class, transaction::commit );
			assertThat( exception.getMessage(), containsString( "beforeCompletion" ) );

			transaction.rollback();

			verify( process ).doAfterTransactionCompletion( eq( false ), any() );
			verify( synchronization ).beforeCompletion();
			verify( synchronization ).afterCompletion( Status.STATUS_UNKNOWN );
			verify( observer ).afterCompletion( false, false );
			verifyNoMoreInteractions( process, synchronization, observer );
		} );
	}

	@Test
	public void rollbackFailureIsSuppressedOnBeforeCompletionFailure() {
		inSession( session -> {
			Transaction transaction = session.beginTransaction();
			Synchronization synchronization = mock( Synchronization.class );
			doThrow( new IllegalStateException( "before completion failure" ) ).when( synchronization ).beforeCompletion();
			doThrow( new IllegalStateException( "after completion failure" ) ).when( synchronization ).afterCompletion( anyInt() );
			transaction.registerSynchronization( synchronization );
			session.doWork( Connection::close );

			RuntimeException exception = assertThrows( RuntimeException.class, transaction::commit );
			assertThat( exception.getMessage(), containsString( "beforeCompletion" ) );

			Throwable[] suppressed = exception.getSuppressed();
			assertEquals( 1, suppressed.length );
			assertThat( suppressed[0], instanceOf( TransactionException.class ) );
			assertThat( suppressed[0].getMessage(), containsString( "Unable to rollback against JDBC Connection" ) );

			Throwable[] nested = suppressed[0].getSuppressed();
			assertEquals( 1, nested.length );
			assertThat( nested[0].getMessage(), containsString( "afterCompletion" ) );

			verify( synchronization ).beforeCompletion();
			verify( synchronization ).afterCompletion( Status.STATUS_UNKNOWN );
			verifyNoMoreInteractions( synchronization );
		} );
	}

	@Test
	public void afterCompletionFailureIsSuppressedOnBeforeCompletionFailure() {
		inSession( session -> {
			Transaction transaction = session.beginTransaction();
			Synchronization synchronization = mock( Synchronization.class );
			doThrow( new IllegalStateException( "before completion failure" ) ).when( synchronization ).beforeCompletion();
			doThrow( new IllegalStateException( "after completion failure" ) ).when( synchronization ).afterCompletion( anyInt() );
			transaction.registerSynchronization( synchronization );

			RuntimeException exception = assertThrows( RuntimeException.class, transaction::commit );
			assertThat( exception.getMessage(), containsString( "beforeCompletion" ) );

			Throwable[] suppressed = exception.getSuppressed();
			assertEquals( 1, suppressed.length );
			assertThat( suppressed[0].getMessage(), containsString( "afterCompletion" ) );

			verify( synchronization ).beforeCompletion();
			verify( synchronization ).afterCompletion( Status.STATUS_UNKNOWN );
			verifyNoMoreInteractions( synchronization );
		} );
	}

	@Override
	protected Class<?>[] getAnnotatedClasses() {
		return new Class<?>[] { SimpleEntity.class };
	}

	@Override
	protected boolean isCleanupTestDataRequired() {
		return true;
	}

	@Entity(name = "SimpleEntity")
	public static class SimpleEntity {
		@Id
		@GeneratedValue
		private Integer id;

		private String name;

		SimpleEntity() {
		}

		SimpleEntity(String name) {
			this.name = name;
		}

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}
}
