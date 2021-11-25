/*
 *    Copyright (c) 2021, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.passwordless;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import io.supertokens.Main;
import io.supertokens.config.Config;
import io.supertokens.passwordless.exceptions.ExpiredUserInputCodeException;
import io.supertokens.passwordless.exceptions.IncorrectUserInputCodeException;
import io.supertokens.passwordless.exceptions.RestartFlowException;
import io.supertokens.pluginInterface.emailpassword.exceptions.DuplicateEmailException;
import io.supertokens.pluginInterface.emailpassword.exceptions.DuplicateUserIdException;
import io.supertokens.pluginInterface.emailpassword.exceptions.UnknownUserIdException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.exceptions.StorageTransactionLogicException;
import io.supertokens.pluginInterface.passwordless.PasswordlessCode;
import io.supertokens.pluginInterface.passwordless.PasswordlessDevice;
import io.supertokens.pluginInterface.passwordless.UserInfo;
import io.supertokens.pluginInterface.passwordless.exception.DuplicateCodeIdException;
import io.supertokens.pluginInterface.passwordless.exception.DuplicateDeviceIdHashException;
import io.supertokens.pluginInterface.passwordless.exception.DuplicateLinkCodeHashException;
import io.supertokens.pluginInterface.passwordless.exception.DuplicatePhoneNumberException;
import io.supertokens.pluginInterface.passwordless.exception.UnknownDeviceIdHash;
import io.supertokens.pluginInterface.passwordless.sqlStorage.PasswordlessSQLStorage;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.utils.Utils;

public class Passwordless {
    // We are storing the "alphabets" like this because we remove a few characters from the normal English alphabet.
    // e.g.: remove easy to confuse chars (oO0, Il)
    private static final String USER_INPUT_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final String USER_INPUT_CODE_NUM_CHARS = "123456789";

    private static Character getRandomAlphaChar(SecureRandom generator) {
        return USER_INPUT_CODE_ALPHABET.charAt(generator.nextInt(USER_INPUT_CODE_ALPHABET.length()));
    }

    private static Character getRandomNumChar(SecureRandom generator) {
        return USER_INPUT_CODE_NUM_CHARS.charAt(generator.nextInt(USER_INPUT_CODE_NUM_CHARS.length()));
    }

    public static CreateCodeResponse createCode(Main main, String email, String phoneNumber, @Nullable String deviceId,
            @Nullable String userInputCode) throws RestartFlowException, DuplicateLinkCodeHashException,
            StorageQueryException, NoSuchAlgorithmException, InvalidKeyException {
        PasswordlessSQLStorage passwordlessStorage = StorageLayer.getPasswordlessStorage(main);
        if (deviceId == null) {
            while (true) {
                CreateCodeInfo info = CreateCodeInfo.generate(userInputCode);
                try {
                    passwordlessStorage.createDeviceWithCode(email, phoneNumber, info.code);

                    return info.resp;
                } catch (DuplicateLinkCodeHashException | DuplicateCodeIdException | DuplicateDeviceIdHashException e) {
                    // These are retryable, so ignored here.
                    // DuplicateLinkCodeHashException is also always retryable, because linkCodeHash depends on the
                    // deviceId which is generated again during the retry
                }
            }
        } else {
            while (true) {
                CreateCodeInfo info = CreateCodeInfo.generate(userInputCode, deviceId);
                try {
                    passwordlessStorage.createCode(info.code);

                    return info.resp;
                } catch (DuplicateLinkCodeHashException e) {
                    if (userInputCode != null) {
                        // We only need to rethrow if the user supplied both the deviceId and the
                        // userInputCode,
                        // because in that case the linkCodeHash will always be the same.
                        throw e;
                    }
                    // It's retrieable otherwise
                } catch (UnknownDeviceIdHash e) {
                    throw new RestartFlowException();
                } catch (DuplicateCodeIdException e) {
                    // Retryable, so ignored here.
                }
            }
        }
    }

    private static String generateUserInputCode() {
        // This logic is based on the idea that we wanted to incorporate letters as well
        // as numbers in the code.
        // We are allowing at most 2 letters in a row, to try and avoid generating slurs
        // or other abusive codes.

        // Note: this implementation gives an equal chance to either numbers or letters,
        // so the probability of any
        // character is lower than the probability of a number, but the distribution is
        // uniform inside both alphabets.

        SecureRandom generator = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        int prevAlphaCharCount = 0;
        for (int i = 0; i < 6; ++i) {
            if ((i < 2 || prevAlphaCharCount < 2) && generator.nextBoolean()) {
                ++prevAlphaCharCount;
                sb.append(getRandomAlphaChar(generator));
            } else {
                prevAlphaCharCount = 0;
                sb.append(getRandomNumChar(generator));
            }
        }
        return sb.toString();
    }

    public static ConsumeCodeResponse consumeCode(Main main, String deviceId, String userInputCode, String linkCode)
            throws RestartFlowException, ExpiredUserInputCodeException, IncorrectUserInputCodeException,
            StorageTransactionLogicException, StorageQueryException, NoSuchAlgorithmException, InvalidKeyException {
        PasswordlessSQLStorage passwordlessStorage = StorageLayer.getPasswordlessStorage(main);
        long passwordlessCodeLifetime = Config.getConfig(main).getPasswordlessCodeLifetime();
        int maxCodeInputAttempts = Config.getConfig(main).getPasswordlessMaxCodeInputAttempts();

        String deviceIdHash;
        String linkCodeHash;
        if (linkCode != null) {
            byte[] linkCodeBytes = Base64.getUrlDecoder().decode(linkCode);
            linkCodeHash = Utils.hashSHA256Base64(linkCodeBytes);

            PasswordlessCode code = passwordlessStorage.getCodeByLinkCodeHash(linkCodeHash);
            if (code == null || code.createdAt < (System.currentTimeMillis() - passwordlessCodeLifetime)) {
                throw new RestartFlowException();
            }
            deviceIdHash = code.deviceIdHash;
        } else {
            byte[] deviceIdBytes = Base64.getDecoder().decode(deviceId);
            deviceIdHash = Utils.hashSHA256Base64UrlSafe(deviceIdBytes);

            byte[] linkCodeBytes = Utils.hmacSHA256(deviceIdBytes, userInputCode);
            linkCodeHash = Utils.hashSHA256Base64(linkCodeBytes);
        }

        PasswordlessDevice consumedDevice;
        try {
            consumedDevice = passwordlessStorage.startTransaction(con -> {
                PasswordlessDevice device = passwordlessStorage.getDevice_Transaction(con, deviceIdHash);
                if (device == null) {
                    throw new StorageTransactionLogicException(new RestartFlowException());
                }
                if (device.failedAttempts >= maxCodeInputAttempts) {
                    passwordlessStorage.deleteDevice_Transaction(con, deviceIdHash);
                    passwordlessStorage.commitTransaction(con);
                    throw new StorageTransactionLogicException(new RestartFlowException());
                }

                PasswordlessCode code = passwordlessStorage.getCodeByLinkCodeHash_Transaction(con, linkCodeHash);
                if (code == null || code.createdAt < System.currentTimeMillis() - passwordlessCodeLifetime) {
                    if (deviceId != null) {
                        // If we get here, it means that the user tried to use a userInputCode, but it was incorrect or
                        // the code expired. This means that we need to increment failedAttempts or clean up the device
                        // if it would exceed the configured max.
                        if (device.failedAttempts + 1 >= maxCodeInputAttempts) {
                            passwordlessStorage.deleteDevice_Transaction(con, deviceIdHash);
                            passwordlessStorage.commitTransaction(con);
                            throw new StorageTransactionLogicException(new RestartFlowException());
                        } else {
                            passwordlessStorage.incrementDeviceFailedAttemptCount_Transaction(con, deviceIdHash);
                            passwordlessStorage.commitTransaction(con);

                            if (code != null) {
                                throw new StorageTransactionLogicException(new ExpiredUserInputCodeException(
                                        device.failedAttempts + 1, maxCodeInputAttempts));
                            } else {
                                throw new StorageTransactionLogicException(new IncorrectUserInputCodeException(
                                        device.failedAttempts + 1, maxCodeInputAttempts));
                            }
                        }
                    }
                    throw new StorageTransactionLogicException(new RestartFlowException());
                }

                if (device.email != null) {
                    passwordlessStorage.deleteDevicesByEmail_Transaction(con, device.email);
                } else if (device.phoneNumber != null) {
                    passwordlessStorage.deleteDevicesByPhoneNumber_Transaction(con, device.phoneNumber);
                }

                passwordlessStorage.commitTransaction(con);
                return device;
            });
        } catch (StorageTransactionLogicException e) {
            if (e.actualException instanceof ExpiredUserInputCodeException) {
                throw (ExpiredUserInputCodeException) e.actualException;
            }
            if (e.actualException instanceof IncorrectUserInputCodeException) {
                throw (IncorrectUserInputCodeException) e.actualException;
            }
            if (e.actualException instanceof RestartFlowException) {
                throw (RestartFlowException) e.actualException;
            }
            throw e;
        }

        // Getting here means that we successfully consumed the code
        UserInfo user = consumedDevice.email != null ? passwordlessStorage.getUserByEmail(consumedDevice.email)
                : passwordlessStorage.getUserByPhoneNumber(consumedDevice.phoneNumber);
        if (user == null) {
            while (true) {
                try {
                    String userId = Utils.getUUID();
                    long timeJoined = System.currentTimeMillis();
                    user = new UserInfo(userId, consumedDevice.email, consumedDevice.phoneNumber, timeJoined);
                    passwordlessStorage.createUser(user);
                    return new ConsumeCodeResponse(true, user);
                } catch (DuplicateEmailException | DuplicatePhoneNumberException e) {
                    // Getting these would mean that between getting the user and trying creating it:
                    // 1. the user managed to do a full create+consume flow
                    // 2. the users email or phoneNumber was updated to the new one (including device cleanup)
                    // These should be almost impossibly rare, so it's safe to just ask the user to restart.
                    // Also, both would make the current login fail if done before the transaction
                    // by cleaning up the device/code this consume would've used.
                    throw new RestartFlowException();
                } catch (DuplicateUserIdException e) {
                    // We can retry..
                }
            }
        } else {
            // We do not need this cleanup if we are creating the user, since it uses the email/phoneNumber of the
            // device, which has already been cleaned up
            if (user.email != null && !user.email.equals(consumedDevice.email)) {
                removeCodesByEmail(main, user.email);
            }
            if (user.phoneNumber != null && !user.phoneNumber.equals(consumedDevice.phoneNumber)) {
                removeCodesByPhoneNumber(main, user.phoneNumber);
            }
        }
        return new ConsumeCodeResponse(false, user);
    }

    public static void removeCode(Main main, String codeId)
            throws StorageQueryException, StorageTransactionLogicException {
        PasswordlessSQLStorage passwordlessStorage = StorageLayer.getPasswordlessStorage(main);

        PasswordlessCode code = passwordlessStorage.getCode(codeId);

        if (code == null) {
            return;
        }

        passwordlessStorage.startTransaction(con -> {
            // Locking the device
            passwordlessStorage.getDevice_Transaction(con, code.deviceIdHash);

            PasswordlessCode[] allCodes = passwordlessStorage.getCodesOfDevice_Transaction(con, code.deviceIdHash);
            if (!Stream.of(allCodes).anyMatch(code::equals)) {
                // Already deleted
                return null;
            }

            if (allCodes.length == 1) {
                // If the device contains only the current code we should delete the device as well.
                passwordlessStorage.deleteDevice_Transaction(con, code.deviceIdHash);
            } else {
                // Otherwise we can just delete the code
                passwordlessStorage.deleteCode_Transaction(con, codeId);
            }
            passwordlessStorage.commitTransaction(con);
            return null;
        });
    }

    public static void removeCodesByEmail(Main main, String email)
            throws StorageQueryException, StorageTransactionLogicException {
        PasswordlessSQLStorage passwordlessStorage = StorageLayer.getPasswordlessStorage(main);

        passwordlessStorage.startTransaction(con -> {
            passwordlessStorage.deleteDevicesByEmail_Transaction(con, email);
            passwordlessStorage.commitTransaction(con);
            return null;
        });
    }

    public static void removeCodesByPhoneNumber(Main main, String phoneNumber)
            throws StorageQueryException, StorageTransactionLogicException {
        PasswordlessSQLStorage passwordlessStorage = StorageLayer.getPasswordlessStorage(main);

        passwordlessStorage.startTransaction(con -> {
            passwordlessStorage.deleteDevicesByPhoneNumber_Transaction(con, phoneNumber);
            passwordlessStorage.commitTransaction(con);
            return null;
        });
    }

    public static UserInfo getUserById(Main main, String userId) throws StorageQueryException {
        return StorageLayer.getPasswordlessStorage(main).getUserById(userId);
    }

    public static UserInfo getUserByPhoneNumber(Main main, String phoneNumber) throws StorageQueryException {
        return StorageLayer.getPasswordlessStorage(main).getUserByPhoneNumber(phoneNumber);
    }

    public static UserInfo getUserByEmail(Main main, String email) throws StorageQueryException {
        return StorageLayer.getPasswordlessStorage(main).getUserByEmail(email);
    }

    public static void updateUser(Main main, String userId, FieldUpdate emailUpdate, FieldUpdate phoneNumberUpdate)
            throws StorageQueryException, UnknownUserIdException, DuplicateEmailException,
            DuplicatePhoneNumberException {
        PasswordlessSQLStorage storage = StorageLayer.getPasswordlessStorage(main);

        // We do not lock the user here, because we decided that even if the device cleanup used outdated information
        // it wouldn't leave the system in an incosistent state/cause problems.
        UserInfo user = storage.getUserById(userId);
        if (user == null) {
            throw new UnknownUserIdException();
        }
        try {
            storage.startTransaction(con -> {

                if (emailUpdate != null && !Objects.equals(emailUpdate.newValue, user.email)) {
                    try {
                        storage.updateUserEmail_Transaction(con, userId, emailUpdate.newValue);
                    } catch (UnknownUserIdException | DuplicateEmailException e) {
                        throw new StorageTransactionLogicException(e);
                    }
                    if (user.email != null) {
                        storage.deleteDevicesByEmail_Transaction(con, user.email);
                    }
                    if (emailUpdate.newValue != null) {
                        storage.deleteDevicesByEmail_Transaction(con, emailUpdate.newValue);
                    }
                }
                if (phoneNumberUpdate != null && !Objects.equals(phoneNumberUpdate.newValue, user.phoneNumber)) {
                    try {
                        storage.updateUserPhoneNumber_Transaction(con, userId, phoneNumberUpdate.newValue);
                    } catch (UnknownUserIdException | DuplicatePhoneNumberException e) {
                        throw new StorageTransactionLogicException(e);
                    }
                    if (user.phoneNumber != null) {
                        storage.deleteDevicesByPhoneNumber_Transaction(con, user.phoneNumber);
                    }
                    if (phoneNumberUpdate.newValue != null) {
                        storage.deleteDevicesByPhoneNumber_Transaction(con, phoneNumberUpdate.newValue);
                    }
                }
                storage.commitTransaction(con);
                return null;
            });
        } catch (StorageTransactionLogicException e) {
            if (e.actualException instanceof UnknownUserIdException) {
                throw (UnknownUserIdException) e.actualException;
            }

            if (e.actualException instanceof DuplicateEmailException) {
                throw (DuplicateEmailException) e.actualException;
            }

            if (e.actualException instanceof DuplicatePhoneNumberException) {
                throw (DuplicatePhoneNumberException) e.actualException;
            }
        }
    }

    // This class represents an optional update that can have null as a new value.
    // By passing null instead of this object, we can signify no-update, while passing the object
    // with null (or a new value) can request an update to that value.
    // This is like a specifically named Optional.
    public static class FieldUpdate {
        public final String newValue;

        public FieldUpdate(String newValue) {
            this.newValue = newValue;
        }
    }

    public static class CreateCodeResponse {
        public String deviceIdHash;
        public String codeId;
        public String deviceId;
        public String userInputCode;
        public String linkCode;
        public long timeCreated;

        public CreateCodeResponse(String deviceIdHash, String codeId, String deviceId, String userInputCode,
                String linkCode, long timeCreated) {
            this.deviceIdHash = deviceIdHash;
            this.codeId = codeId;
            this.deviceId = deviceId;
            this.userInputCode = userInputCode;
            this.linkCode = linkCode;
            this.timeCreated = timeCreated;
        }
    }

    public static class ConsumeCodeResponse {
        public boolean createdNewUser;
        public UserInfo user;

        public ConsumeCodeResponse(boolean createdNewUser, UserInfo user) {
            this.createdNewUser = createdNewUser;
            this.user = user;
        }
    }

    private static class CreateCodeInfo {
        public final CreateCodeResponse resp;
        public final PasswordlessCode code;

        private CreateCodeInfo(String codeId, String deviceId, String deviceIdHash, String linkCode,
                String linkCodeHash, String userInputCode, Long createdAt) {
            this.code = new PasswordlessCode(codeId, deviceIdHash, linkCodeHash, createdAt);
            this.resp = new CreateCodeResponse(deviceIdHash, codeId, deviceId, userInputCode, linkCode, createdAt);
        }

        public static CreateCodeInfo generate(String userInputCode)
                throws InvalidKeyException, NoSuchAlgorithmException {
            SecureRandom generator = new SecureRandom();
            byte[] deviceIdBytes = new byte[32];
            generator.nextBytes(deviceIdBytes);
            return generate(userInputCode, deviceIdBytes);
        }

        public static CreateCodeInfo generate(String userInputCode, String deviceId)
                throws InvalidKeyException, NoSuchAlgorithmException {
            byte[] deviceIdBytes = Base64.getDecoder().decode(deviceId);
            return generate(userInputCode, deviceIdBytes);
        }

        public static CreateCodeInfo generate(String userInputCode, byte[] deviceIdBytes)
                throws InvalidKeyException, NoSuchAlgorithmException {
            if (userInputCode == null) {
                userInputCode = generateUserInputCode();
            }

            String codeId = Utils.getUUID();

            String deviceId = Base64.getEncoder().encodeToString(deviceIdBytes);
            String deviceIdHash = Utils.hashSHA256Base64UrlSafe(deviceIdBytes);

            byte[] linkCodeBytes = Utils.hmacSHA256(deviceIdBytes, userInputCode);
            String linkCode = Base64.getUrlEncoder().encodeToString(linkCodeBytes);

            String linkCodeHash = Utils.hashSHA256Base64(linkCodeBytes);

            long createdAt = System.currentTimeMillis();

            return new CreateCodeInfo(codeId, deviceId, deviceIdHash, linkCode, linkCodeHash, userInputCode, createdAt);
        }
    }
}
