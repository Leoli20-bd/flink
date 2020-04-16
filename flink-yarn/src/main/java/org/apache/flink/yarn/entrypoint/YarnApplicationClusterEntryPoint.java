/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.yarn.entrypoint;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.JobID;
import org.apache.flink.client.deployment.application.ApplicationClusterEntryPoint;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.client.deployment.application.ClassPathPackagedProgramRetriever;
import org.apache.flink.client.deployment.application.executors.EmbeddedExecutor;
import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.client.program.PackagedProgramRetriever;
import org.apache.flink.configuration.ConfigUtils;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.PipelineOptionsInternal;
import org.apache.flink.runtime.entrypoint.ClusterEntrypoint;
import org.apache.flink.runtime.jobmanager.HighAvailabilityMode;
import org.apache.flink.runtime.util.EnvironmentInformation;
import org.apache.flink.runtime.util.JvmShutdownSafeguard;
import org.apache.flink.runtime.util.SignalHandler;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;
import org.apache.flink.yarn.configuration.YarnConfigOptions;

import org.apache.hadoop.yarn.api.ApplicationConstants;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;

import static org.apache.flink.runtime.util.ClusterEntrypointUtils.tryFindUserLibDirectory;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * An {@link ApplicationClusterEntryPoint} for Yarn.
 */
@Internal
public final class YarnApplicationClusterEntryPoint extends ApplicationClusterEntryPoint {

	public static final JobID ZERO_JOB_ID = new JobID(0, 0);

	private YarnApplicationClusterEntryPoint(
			final Configuration configuration,
			final PackagedProgram program) {
		super(configuration, program, YarnResourceManagerFactory.getInstance());
	}

	public static void main(final String[] args) {
		// startup checks and logging
		EnvironmentInformation.logEnvironmentInfo(LOG, YarnApplicationClusterEntryPoint.class.getSimpleName(), args);
		SignalHandler.register(LOG);
		JvmShutdownSafeguard.installAsShutdownHook(LOG);

		Map<String, String> env = System.getenv();

		final String workingDirectory = env.get(ApplicationConstants.Environment.PWD.key());
		Preconditions.checkArgument(
				workingDirectory != null,
				"Working directory variable (%s) not set",
				ApplicationConstants.Environment.PWD.key());

		try {
			YarnEntrypointUtils.logYarnEnvironmentInformation(env, LOG);
		} catch (IOException e) {
			LOG.warn("Could not log YARN environment information.", e);
		}

		final Configuration configuration = YarnEntrypointUtils.loadConfiguration(workingDirectory, env);

		PackagedProgram program = null;
		try {
			program = getPackagedProgram(configuration);
		} catch (Exception e) {
			LOG.error("Could not create application program.", e);
			System.exit(1);
		}

		final JobID  jobId = createJobIdForCluster(configuration);
		configuration.set(PipelineOptionsInternal.PIPELINE_FIXED_JOB_ID, jobId.toHexString());

		configuration.set(DeploymentOptions.TARGET, EmbeddedExecutor.NAME);
		ConfigUtils.encodeCollectionToConfig(configuration, PipelineOptions.JARS, program.getJobJarAndDependencies(), URL::toString);
		ConfigUtils.encodeCollectionToConfig(configuration, PipelineOptions.CLASSPATHS, program.getClasspaths(), URL::toString);

		YarnApplicationClusterEntryPoint yarnApplicationClusterEntrypoint =
				new YarnApplicationClusterEntryPoint(configuration, program);

		ClusterEntrypoint.runClusterEntrypoint(yarnApplicationClusterEntrypoint);
	}

	private static PackagedProgram getPackagedProgram(final Configuration configuration) throws IOException, FlinkException {

		final ApplicationConfiguration applicationConfiguration =
				ApplicationConfiguration.fromConfiguration(configuration);

		final PackagedProgramRetriever programRetriever = getPackagedProgramRetriever(
				configuration,
				applicationConfiguration.getProgramArguments(),
				applicationConfiguration.getApplicationClassName());
		return programRetriever.getPackagedProgram();
	}

	private static PackagedProgramRetriever getPackagedProgramRetriever(
			final Configuration configuration,
			final String[] programArguments,
			@Nullable final String jobClassName) throws IOException {
		final ClassPathPackagedProgramRetriever.Builder retrieverBuilder =
				ClassPathPackagedProgramRetriever
						.newBuilder(programArguments)
						.setJobClassName(jobClassName);
		getUsrLibDir(configuration).ifPresent(retrieverBuilder::setUserLibDirectory);
		return retrieverBuilder.build();
	}

	private static Optional<File> getUsrLibDir(final Configuration configuration) {
		final YarnConfigOptions.UserJarInclusion userJarInclusion = configuration
				.getEnum(YarnConfigOptions.UserJarInclusion.class, YarnConfigOptions.CLASSPATH_INCLUDE_USER_JAR);
		final Optional<File> userLibDir = tryFindUserLibDirectory();

		checkState(
				userJarInclusion != YarnConfigOptions.UserJarInclusion.DISABLED || userLibDir.isPresent(),
				"The %s is set to %s. But the usrlib directory does not exist.",
				YarnConfigOptions.CLASSPATH_INCLUDE_USER_JAR.key(),
				YarnConfigOptions.UserJarInclusion.DISABLED);

		return userJarInclusion == YarnConfigOptions.UserJarInclusion.DISABLED ? userLibDir : Optional.empty();
	}

	private static JobID createJobIdForCluster(Configuration globalConfiguration) {
		if (HighAvailabilityMode.isHighAvailabilityModeActivated(globalConfiguration)) {
			return ZERO_JOB_ID;
		} else {
			return JobID.generate();
		}
	}
}
