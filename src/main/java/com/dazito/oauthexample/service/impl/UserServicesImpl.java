package com.dazito.oauthexample.service.impl;

import com.dazito.oauthexample.config.oauth.UserDetailsConfig;
import com.dazito.oauthexample.dao.AccountRepository;
import com.dazito.oauthexample.dao.OrganizationRepo;
import com.dazito.oauthexample.dao.StorageRepository;
import com.dazito.oauthexample.model.AccountEntity;
import com.dazito.oauthexample.model.Content;
import com.dazito.oauthexample.model.Organization;
import com.dazito.oauthexample.model.type.UserRole;
import com.dazito.oauthexample.service.FileService;
import com.dazito.oauthexample.service.UserService;
import com.dazito.oauthexample.service.dto.request.AccountDto;
import com.dazito.oauthexample.service.dto.request.DeleteAccountDto;
import com.dazito.oauthexample.service.dto.request.EditPersonalDataDto;
import com.dazito.oauthexample.service.dto.request.OrganizationDto;
import com.dazito.oauthexample.service.dto.response.EditedEmailNameDto;
import com.dazito.oauthexample.service.dto.response.EditedPasswordDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Optional;

@Service(value = "userService")
public class UserServicesImpl implements UserService {

    @Value("${root.path}")
    String root;
    @Value("${content.admin}")
    String contentName;
    @Resource(name = "conversionService")
    ConversionService conversionService;
    @Autowired
    StorageRepository storageRepository;

    private final FileService fileService;
    private final AccountRepository accountRepository;
    private final OrganizationRepo organizationRepo;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserServicesImpl(AccountRepository accountRepository, OrganizationRepo organizationRepo, PasswordEncoder passwordEncoder, FileService fileService) {
        this.accountRepository = accountRepository;
        this.organizationRepo = organizationRepo;
        this.passwordEncoder = passwordEncoder;
        this.fileService = fileService;
    }

    // Change user password
    @Override
    public EditedPasswordDto editPassword(Long id, String newPassword, String rawOldPassword) {
        AccountEntity foundedUser = findByIdAccountRepo(id);
        if (!isEmpty(newPassword)) return null;
        if (!isEmpty(rawOldPassword)) return null;

        AccountEntity currentUser = getCurrentUser();
        String passwordCurrentUser = currentUser.getPassword();
        AccountEntity accountToBeEdited;
        String encodedPassword = passwordEncode(newPassword);
        boolean matches;

        if (id == null) {
            accountToBeEdited = currentUser;
            matches = checkMatches(rawOldPassword, passwordCurrentUser);
            return savePassword(matches, encodedPassword, accountToBeEdited);
        }

        if (!adminRightsCheck(getCurrentUser())) return null; // current user is not Admin;

        String organizationName = getOrganizationNameByUser(foundedUser);
        if (organizationMatch(organizationName, currentUser)) return null; // organization current user and user from account dto is not match

        accountToBeEdited = getCurrentUser();
        matches = checkMatches(rawOldPassword, passwordCurrentUser);
        return savePassword(matches, encodedPassword, accountToBeEdited);
    }

    // Change user email and name, documentation on it in UserService
    @Override
    public EditedEmailNameDto editPersonData(Long id, EditPersonalDataDto personalData) {

        String newName;
        String newEmail;

        AccountEntity currentUser = getCurrentUser();

        newEmail = personalData.getNewEmail();
        boolean checkedEmailOnNull = isEmpty(newEmail);
        if (!checkedEmailOnNull) newEmail = currentUser.getEmail();

        if (findUserByEmail(newEmail) != null) return null; // user with such email exist;

        newName = personalData.getNewName();
        boolean checkedNameOnNull = isEmpty(newName);
        if (!checkedNameOnNull) newName = currentUser.getUsername();

        AccountEntity accountWithNewEmail;
        if (id == null) {
            accountWithNewEmail = accountEditedSetEmailAndName(newEmail, newName, currentUser);
            accountRepository.saveAndFlush(accountWithNewEmail);
            return responseDto(accountWithNewEmail);
        }

        if (!adminRightsCheck(currentUser)) return null; // current user is not Admin;

        AccountEntity foundedAccount = findByIdAccountRepo(id);
        String organizationName = getOrganizationNameByUser(foundedAccount);

        if (!organizationMatch(organizationName, currentUser)) return null; // organization current user and user from account dto is not match

        accountWithNewEmail = accountEditedSetEmailAndName(newEmail, newName, currentUser);
        accountRepository.saveAndFlush(accountWithNewEmail);
        return responseDto(accountWithNewEmail);
    }

    // Create a new user
    @Override
    public EditedEmailNameDto createUser(AccountDto accountDto) {

        AccountEntity currentUser = getCurrentUser();
        String email = accountDto.getEmail();
        String organizationName = accountDto.getOrganizationName();

        if (findUserByEmail(email) != null) return null; // user with such email exist;
        if (!adminRightsCheck(currentUser)) return null; // current user is not Admin;
        if (!organizationMatch(organizationName, currentUser)) return null; // organization current user and user from account dto is not match

        String password = accountDto.getPassword();
        String userName = accountDto.getUsername();
        boolean isActivated = accountDto.getIsActivated();
        UserRole role = accountDto.getRole();

        AccountEntity newUser = new AccountEntity();
        newUser.setEmail(email);

        String encodedPassword = passwordEncode(password);

        Organization organization = findOrganizationByName(organizationName);

        newUser.setPassword(encodedPassword);
        newUser.setUsername(userName);
        newUser.setIsActivated(isActivated);
        newUser.setRole(role);
        newUser.setOrganization(organization);

        Content rootContent = null;

        if (getCountStorageWithOwnerNullAndNotNullOrganization() < 1 || role.equals(UserRole.USER)) {
            rootContent = fileService.createContent(newUser);
        }

        newUser.setContent(rootContent);
        accountRepository.saveAndFlush(newUser);

        return responseDto(newUser);
    }

    // Delete user by id or current user
    @Override
    public void deleteUser(Long id, DeleteAccountDto accountDto) {
        String email;
        String password;
        AccountEntity currentUser = getCurrentUser();
        if (id == null) {
            email = accountDto.getEmail();
            password = accountDto.getRawPassword();

            boolean checkEmail = getCurrentUser().getEmail().equals(email);
            if (!checkEmail) return; // email not matches

            String encodedPassword = getCurrentUser().getPassword();
            boolean checkPassword = passwordEncoder.matches(password, encodedPassword);
            if (!checkPassword) return; // password not matches

            AccountEntity account = findUserByEmail(email);
            accountRepository.delete(account);
        }

        if (!adminRightsCheck(getCurrentUser())) return; // current user is not Admin;

        AccountEntity foundedUser = findByIdAccountRepo(id);
        String organizationName = getOrganizationNameByUser(foundedUser);
        if (organizationMatch(organizationName, currentUser))
            return; // organization current user and user from account dto is not match

        accountRepository.delete(foundedUser);
    }

    @Override
    public EditedPasswordDto savePassword(boolean matches, String encodedPassword, AccountEntity accountToBeEdited) {
        if (matches) {
            saveEncodedPassword(encodedPassword, accountToBeEdited);
            return convertToResponsePassword(accountToBeEdited.getPassword());
        }
        return null;
    }

    @Override
    public void saveEncodedPassword(String encodedPassword, AccountEntity accountToBeEdited) {
        accountToBeEdited.setPassword(encodedPassword);
        accountRepository.saveAndFlush(accountToBeEdited);
    }

    @Override
    public String passwordEncode(String newPassword) {
        return passwordEncoder.encode(newPassword);
    }

    @Override
    public boolean checkMatches(String rawOldPassword, String passwordCurrentUser) {
        return passwordEncoder.matches(rawOldPassword, passwordCurrentUser);
    }

    @Override
    public EditedPasswordDto convertToResponsePassword(String newPassword) {
        EditedPasswordDto editedPasswordDto = new EditedPasswordDto();
        editedPasswordDto.setPassword(newPassword);
        return editedPasswordDto;
    }

    @Override
    public AccountEntity accountEditedSetEmailAndName(String newEmail, String newName, AccountEntity accountToBeEdited) {
        accountToBeEdited.setEmail(newEmail);
        accountToBeEdited.setUsername(newName);
        return accountToBeEdited;
    }

    @Override
    public AccountEntity findUserByEmail(String email) {
        Optional<AccountEntity> foundedUser = accountRepository.findUserByEmail(email);
        if (isOptionalNotNull(foundedUser)) return foundedUser.get();
        return null;
    }

    @Override
    public boolean isEmpty(String val) {
        return val != null && !val.equals("");
    }

    @Override
    public boolean isOptionalNotNull(Optional val) {
        return val.isPresent();
    }




    @Override
    public AccountEntity findByIdAccountRepo(Long id) {
        if (id == null) return null;
        Optional<AccountEntity> foundByIdOptional = accountRepository.findById(id);
        boolean checkedOnNull = isOptionalNotNull(foundByIdOptional);
        if (checkedOnNull) return foundByIdOptional.get();
        return null;
    }

    @Override
    public boolean adminRightsCheck(AccountEntity entity) {
        UserRole role = entity.getRole();
        return role == UserRole.ADMIN;
    }

    @Override
    public boolean organizationMatch(String userOrganization, AccountEntity currentUser) {
        String userCurrentOrganization = getOrganizationNameCurrentUser(currentUser);
        return userOrganization.equals(userCurrentOrganization);
    }

    @Override
    public String getOrganizationNameCurrentUser(AccountEntity currentUser) {
        return currentUser.getOrganization().getOrganizationName();
    }

    @Override
    public AccountEntity getCurrentUser() {
        Long id = ((UserDetailsConfig) (SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getUser().getId();
        Optional<AccountEntity> optionalById = accountRepository.findById(id);
        return optionalById.orElse(null);
    }

    @Override
    public AccountDto convertAccountToDto(AccountEntity accountEntity) {
        return conversionService.convert(accountEntity, AccountDto.class);
    }

    @Override
    public AccountEntity convertAccountToEntity(AccountDto accountDto) {
        return conversionService.convert(accountDto, AccountEntity.class);
    }

    @Override
    public AccountDto addToAccountDtoOrganization(AccountEntity foundedUser) {
        Organization organization = foundedUser.getOrganization();
        OrganizationDto convertedOrganization = conversionService.convert(organization, OrganizationDto.class);
        AccountDto accountDto = conversionService.convert(foundedUser, AccountDto.class);
        accountDto.setOrganizationName(convertedOrganization.getOrganizationName());
        return accountDto;
    }

    @Override
    public String getOrganizationNameByUser(AccountEntity accountEntity) {
        return accountEntity.getOrganization().getOrganizationName();
    }

    @Override
    public Organization findOrganizationByName(String organizationName) {
        Optional<Organization> foundedOrganization = organizationRepo.findByOrganizationName(organizationName);
        if (isOptionalNotNull(foundedOrganization)) return foundedOrganization.get();
        return null;
    }

    @Override
    public EditedEmailNameDto responseDto(AccountEntity accountEntity) {
        EditedEmailNameDto editedEmailNameDto = new EditedEmailNameDto();
        editedEmailNameDto.setUsername(accountEntity.getUsername());
        editedEmailNameDto.setEmail(accountEntity.getEmail());
        return editedEmailNameDto;
    }

    public Long getCountStorageWithOwnerNullAndNotNullOrganization(){
        return storageRepository.countStorageElementByOwnerIsNullAndOrganizationIsNotNull();
    }

}